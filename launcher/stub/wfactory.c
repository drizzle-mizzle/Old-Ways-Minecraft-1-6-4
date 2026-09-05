/*
 * W-Factory: запускатель в одном exe.
 *
 * Внутри файла лежит рантайм Java и сам лаунчер, сжатые LZMA. При первом
 * запуске всё распаковывается в папку приложения, дальше запуск идёт сразу.
 * Ничего не устанавливается и не пишется в систему; рядом с exe тоже ничего
 * не появляется — он остаётся одним файлом, который можно держать где угодно.
 *
 *     %LOCALAPPDATA%\W-Factory\runtime\   Java 8
 *     %LOCALAPPDATA%\W-Factory\game\      клиент, ресурсы, миры, настройки
 *
 * Почему не готовый SFX: все стандартные модули 7-Zip распаковывают во
 * временную папку и стирают её после запуска — это установщик, а нужна
 * переносимая папка, которая переживает перезапуск.
 *
 * Почему LZMA, а не zip: Temurin хранит rt.jar без сжатия, и LZMA ужимает
 * его с 60 МБ до 13. Через zip тот же файл дал бы вдвое больше.
 *
 * Хвост exe: [полезная нагрузка][u64 смещение][u64 длина][u32 crc][8 байт метки].
 * Нагрузка — поток .lzma: 5 байт свойств, 8 байт длины, дальше данные.
 * Распакованный блок — цепочка записей:
 *
 *     u32 длина имени (0 — конец), имя UTF-8, u8 вид (0 файл, 1 каталог),
 *     u64 размер, содержимое
 *
 * По длине и crc видно, распакована ли уже эта версия: метка лежит
 * в runtime\.payload.
 */

#include <windows.h>
#include <stdio.h>
#include <stdlib.h>

#include "LzmaDec.h"

#define TRAILER_MAGIC "WFPAY001"
#define TRAILER_SIZE  (8 + 8 + 4 + 8)
#define LZMA_HEADER   (LZMA_PROPS_SIZE + 8)

static const wchar_t *APP_NAME = L"W-Factory";

/* ------------------------------------------------------------- окно хода дел */

static HWND  g_window;
static HFONT g_font;
static double g_part;
static wchar_t g_note[160] = L"";

static void paint(HWND window)
{
    PAINTSTRUCT ps;
    HDC dc = BeginPaint(window, &ps);
    RECT client;
    GetClientRect(window, &client);
    FillRect(dc, &client, (HBRUSH)(COLOR_WINDOW + 1));

    SelectObject(dc, g_font);
    SetBkMode(dc, TRANSPARENT);
    RECT text = client;
    text.left += 18;
    text.top += 18;
    text.right -= 18;
    DrawTextW(dc, g_note, -1, &text, DT_LEFT | DT_TOP | DT_WORDBREAK);

    RECT bar;
    bar.left = 18;
    bar.right = client.right - 18;
    bar.top = client.bottom - 38;
    bar.bottom = bar.top + 14;
    FrameRect(dc, &bar, (HBRUSH)GetStockObject(GRAY_BRUSH));

    RECT fill = bar;
    fill.left += 1;
    fill.top += 1;
    fill.bottom -= 1;
    fill.right = fill.left + (LONG)((bar.right - bar.left - 2) * g_part);
    HBRUSH brush = CreateSolidBrush(RGB(0x3c, 0x78, 0xb4));
    FillRect(dc, &fill, brush);
    DeleteObject(brush);

    EndPaint(window, &ps);
}

static LRESULT CALLBACK window_proc(HWND window, UINT message, WPARAM w, LPARAM l)
{
    if (message == WM_PAINT)
    {
        paint(window);
        return 0;
    }
    if (message == WM_CLOSE)   /* прерывать распаковку на середине нечем */
        return 0;
    return DefWindowProcW(window, message, w, l);
}

/* Системный шрифт интерфейса: иначе Windows подставит древний Tahoma 8. */
static HFONT ui_font(void)
{
    NONCLIENTMETRICSW metrics;
    metrics.cbSize = sizeof(metrics);
    if (SystemParametersInfoW(SPI_GETNONCLIENTMETRICS, sizeof(metrics), &metrics, 0))
        return CreateFontIndirectW(&metrics.lfMessageFont);
    return (HFONT)GetStockObject(DEFAULT_GUI_FONT);
}

static void pump(void)
{
    MSG message;
    while (PeekMessageW(&message, NULL, 0, 0, PM_REMOVE))
    {
        TranslateMessage(&message);
        DispatchMessageW(&message);
    }
}

static void show_window(const wchar_t *note)
{
    WNDCLASSEXW cls;
    ZeroMemory(&cls, sizeof(cls));
    cls.cbSize = sizeof(cls);
    cls.lpfnWndProc = window_proc;
    cls.hInstance = GetModuleHandleW(NULL);
    cls.hCursor = LoadCursor(NULL, IDC_ARROW);
    cls.hbrBackground = (HBRUSH)(COLOR_WINDOW + 1);
    cls.lpszClassName = L"WFactoryUnpack";
    cls.hIcon = LoadIconW(GetModuleHandleW(NULL), MAKEINTRESOURCEW(1));
    cls.hIconSm = cls.hIcon;
    RegisterClassExW(&cls);

    int width = 460, height = 150;
    RECT work;
    SystemParametersInfoW(SPI_GETWORKAREA, 0, &work, 0);
    int x = work.left + (work.right - work.left - width) / 2;
    int y = work.top + (work.bottom - work.top - height) / 2;

    wcsncpy(g_note, note, 159);
    g_font = ui_font();
    g_window = CreateWindowExW(0, cls.lpszClassName, APP_NAME,
                               WS_OVERLAPPED | WS_CAPTION | WS_SYSMENU,
                               x, y, width, height, NULL, NULL, cls.hInstance, NULL);
    ShowWindow(g_window, SW_SHOW);
    UpdateWindow(g_window);
    pump();
}

static void progress(const wchar_t *note, double part)
{
    if (note) wcsncpy(g_note, note, 159);
    g_part = part;
    if (g_window)
    {
        InvalidateRect(g_window, NULL, FALSE);
        UpdateWindow(g_window);
        pump();
    }
}

static void fail(const wchar_t *message)
{
    if (g_window) DestroyWindow(g_window);
    MessageBoxW(NULL, message, APP_NAME, MB_ICONERROR | MB_OK);
    ExitProcess(1);
}

/* --------------------------------------------------------------------- пути */

static void join(wchar_t *out, size_t max, const wchar_t *base, const wchar_t *tail)
{
    _snwprintf(out, max, L"%ls\\%ls", base, tail);
    out[max - 1] = 0;
}

/* Создаёт все недостающие каталоги пути к файлу. */
static void make_parents(wchar_t *path)
{
    for (wchar_t *at = path; *at; at++)
    {
        if (*at != L'\\') continue;
        *at = 0;
        if (wcslen(path) > 3) CreateDirectoryW(path, NULL);
        *at = L'\\';
    }
}

static int exists(const wchar_t *path)
{
    return GetFileAttributesW(path) != INVALID_FILE_ATTRIBUTES;
}

/* Можно ли писать рядом с exe: в Program Files или на защищённом диске — нет. */
static int writable(const wchar_t *dir)
{
    wchar_t probe[MAX_PATH * 2];
    join(probe, MAX_PATH * 2, dir, L".wf-write-test");
    HANDLE file = CreateFileW(probe, GENERIC_WRITE, 0, NULL, CREATE_ALWAYS,
                              FILE_ATTRIBUTE_TEMPORARY | FILE_FLAG_DELETE_ON_CLOSE, NULL);
    if (file == INVALID_HANDLE_VALUE) return 0;
    CloseHandle(file);
    return 1;
}

/* ------------------------------------------------------------ хвост и метка */

struct payload
{
    unsigned long long offset;
    unsigned long long size;
    unsigned int crc;
};

static int read_trailer(const wchar_t *self, struct payload *out)
{
    HANDLE file = CreateFileW(self, GENERIC_READ, FILE_SHARE_READ, NULL,
                              OPEN_EXISTING, 0, NULL);
    if (file == INVALID_HANDLE_VALUE) return 0;

    LARGE_INTEGER size;
    unsigned char tail[TRAILER_SIZE];
    DWORD got = 0;
    int ok = 0;
    if (GetFileSizeEx(file, &size) && size.QuadPart > TRAILER_SIZE)
    {
        LARGE_INTEGER at;
        at.QuadPart = size.QuadPart - TRAILER_SIZE;
        if (SetFilePointerEx(file, at, NULL, FILE_BEGIN)
            && ReadFile(file, tail, TRAILER_SIZE, &got, NULL) && got == TRAILER_SIZE
            && memcmp(tail + 20, TRAILER_MAGIC, 8) == 0)
        {
            memcpy(&out->offset, tail, 8);
            memcpy(&out->size, tail + 8, 8);
            memcpy(&out->crc, tail + 16, 4);
            ok = 1;
        }
    }
    CloseHandle(file);
    return ok;
}

static void marker_path(wchar_t *out, size_t max, const wchar_t *base)
{
    join(out, max, base, L"runtime\\.payload");
}

static int already_unpacked(const wchar_t *base, const struct payload *load)
{
    wchar_t path[MAX_PATH * 2];
    marker_path(path, MAX_PATH * 2, base);
    HANDLE file = CreateFileW(path, GENERIC_READ, FILE_SHARE_READ, NULL,
                              OPEN_EXISTING, 0, NULL);
    if (file == INVALID_HANDLE_VALUE) return 0;

    char stored[64] = { 0 };
    char want[64];
    DWORD got = 0;
    ReadFile(file, stored, sizeof(stored) - 1, &got, NULL);
    CloseHandle(file);
    _snprintf(want, sizeof(want), "%llu-%u", load->size, load->crc);
    return strcmp(stored, want) == 0;
}

static void write_marker(const wchar_t *base, const struct payload *load)
{
    wchar_t path[MAX_PATH * 2];
    char text[64];
    DWORD written = 0;
    marker_path(path, MAX_PATH * 2, base);
    HANDLE file = CreateFileW(path, GENERIC_WRITE, 0, NULL, CREATE_ALWAYS,
                              FILE_ATTRIBUTE_HIDDEN, NULL);
    if (file == INVALID_HANDLE_VALUE) return;
    _snprintf(text, sizeof(text), "%llu-%u", load->size, load->crc);
    WriteFile(file, text, (DWORD)strlen(text), &written, NULL);
    CloseHandle(file);
}

/* ------------------------------------------------------------- распаковка */

static void *lzma_alloc(ISzAllocPtr p, size_t size) { (void)p; return malloc(size); }
static void  lzma_free(ISzAllocPtr p, void *address) { (void)p; free(address); }
static const ISzAlloc g_lzma_alloc = { lzma_alloc, lzma_free };

static unsigned long long read_u64(const unsigned char *at)
{
    unsigned long long v = 0;
    for (int i = 7; i >= 0; i--) v = (v << 8) | at[i];
    return v;
}

static unsigned int read_u32(const unsigned char *at)
{
    return (unsigned int)at[0] | ((unsigned int)at[1] << 8)
         | ((unsigned int)at[2] << 16) | ((unsigned int)at[3] << 24);
}

/* Отклоняем выход за пределы папки: в подменённом файле может быть "..\". */
static int safe_name(const wchar_t *name)
{
    if (!name[0] || name[0] == L'\\' || name[0] == L'/') return 0;
    if (wcsstr(name, L"..")) return 0;
    if (wcschr(name, L':')) return 0;
    return 1;
}

static unsigned char *decompress(const wchar_t *self, const struct payload *load,
                                 unsigned long long *out_size)
{
    HANDLE file = CreateFileW(self, GENERIC_READ, FILE_SHARE_READ, NULL,
                              OPEN_EXISTING, 0, NULL);
    if (file == INVALID_HANDLE_VALUE) fail(L"Не удалось открыть собственный файл.");

    LARGE_INTEGER at;
    at.QuadPart = (long long)load->offset;
    if (!SetFilePointerEx(file, at, NULL, FILE_BEGIN))
        fail(L"Файл повреждён: не нашёл вложенные данные.");

    unsigned char header[LZMA_HEADER];
    DWORD got = 0;
    if (!ReadFile(file, header, LZMA_HEADER, &got, NULL) || got != LZMA_HEADER)
        fail(L"Файл повреждён: вложенные данные не читаются.");

    unsigned long long packed = load->size - LZMA_HEADER;
    unsigned long long unpacked = read_u64(header + LZMA_PROPS_SIZE);
    if (unpacked == 0 || unpacked > 0x40000000ULL)   /* гигабайта хватит с запасом */
        fail(L"Файл повреждён: несуразный размер вложенных данных.");

    unsigned char *source = (unsigned char *)malloc((size_t)packed);
    unsigned char *target = (unsigned char *)malloc((size_t)unpacked);
    if (!source || !target) fail(L"Не хватает памяти для распаковки.");

    unsigned long long left = packed;
    unsigned char *at_source = source;
    while (left > 0)
    {
        DWORD chunk = (DWORD)(left > (1 << 20) ? (1 << 20) : left);
        if (!ReadFile(file, at_source, chunk, &got, NULL) || got == 0)
            fail(L"Файл повреждён: вложенные данные оборвались.");
        at_source += got;
        left -= got;
    }
    CloseHandle(file);

    SizeT target_len = (SizeT)unpacked;
    SizeT source_len = (SizeT)packed;
    ELzmaStatus status;
    SRes res = LzmaDecode(target, &target_len, source, &source_len, header,
                          LZMA_PROPS_SIZE, LZMA_FINISH_END, &status, &g_lzma_alloc);
    free(source);
    if (res != SZ_OK || target_len != unpacked)
        fail(L"Не удалось распаковать вложенные данные.");

    *out_size = unpacked;
    return target;
}

static void unpack(const wchar_t *self, const wchar_t *base, const struct payload *load)
{
    show_window(L"Первый запуск: распаковываю Java рядом с программой…");

    unsigned long long size = 0;
    unsigned char *blob = decompress(self, load, &size);

    unsigned long long at = 0;
    while (at + 4 <= size)
    {
        unsigned int name_len = read_u32(blob + at);
        at += 4;
        if (name_len == 0) break;
        if (at + name_len + 9 > size) fail(L"Вложенные данные повреждены.");

        char name_utf8[MAX_PATH * 2];
        if (name_len >= sizeof(name_utf8)) fail(L"Слишком длинное имя внутри архива.");
        memcpy(name_utf8, blob + at, name_len);
        name_utf8[name_len] = 0;
        at += name_len;

        int is_dir = blob[at];
        at += 1;
        unsigned long long file_size = read_u64(blob + at);
        at += 8;
        if (at + file_size > size) fail(L"Вложенные данные повреждены.");

        wchar_t name[MAX_PATH * 2];
        if (MultiByteToWideChar(CP_UTF8, 0, name_utf8, -1, name, MAX_PATH * 2))
        {
            for (wchar_t *c = name; *c; c++)
                if (*c == L'/') *c = L'\\';

            if (safe_name(name))
            {
                wchar_t path[MAX_PATH * 2];
                join(path, MAX_PATH * 2, base, name);
                make_parents(path);
                if (is_dir)
                {
                    CreateDirectoryW(path, NULL);
                }
                else
                {
                    HANDLE out = CreateFileW(path, GENERIC_WRITE, 0, NULL, CREATE_ALWAYS,
                                             FILE_ATTRIBUTE_NORMAL, NULL);
                    if (out == INVALID_HANDLE_VALUE)
                        fail(L"Не удалось записать файл рядом с программой.\n"
                             L"Перенесите её в папку, куда разрешена запись.");
                    unsigned long long left = file_size;
                    const unsigned char *from = blob + at;
                    while (left > 0)
                    {
                        DWORD chunk = (DWORD)(left > (1 << 20) ? (1 << 20) : left);
                        DWORD written = 0;
                        if (!WriteFile(out, from, chunk, &written, NULL) || written == 0)
                        {
                            CloseHandle(out);
                            fail(L"Не удалось записать файл: закончилось место на диске?");
                        }
                        from += written;
                        left -= written;
                    }
                    CloseHandle(out);
                }
            }
        }
        at += file_size;
        progress(NULL, (double)at / (double)size);
    }

    free(blob);
    write_marker(base, load);
    if (g_window)
    {
        DestroyWindow(g_window);
        g_window = NULL;
        pump();
    }
}

/*
 * Куда класть рантайм и игру: в папку приложения внутри профиля.
 *
 * Рядом с exe не создаётся ничего — он остаётся одним файлом, который можно
 * держать хоть в «Загрузках». Профиль берётся локальный, а не перемещаемый:
 * четыреста мегабайт игры незачем таскать за пользователем по сети.
 */
static void choose_base(wchar_t *out, size_t max)
{
    static const wchar_t *VARIABLES[] = { L"LOCALAPPDATA", L"APPDATA" };
    for (int i = 0; i < 2; i++)
    {
        wchar_t profile[MAX_PATH * 2], target[MAX_PATH * 2];
        DWORD n = GetEnvironmentVariableW(VARIABLES[i], profile, MAX_PATH * 2);
        if (n == 0 || n >= MAX_PATH * 2) continue;
        join(target, MAX_PATH * 2, profile, APP_NAME);
        CreateDirectoryW(target, NULL);
        if (writable(target))
        {
            wcsncpy(out, target, max);
            out[max - 1] = 0;
            return;
        }
    }
    fail(L"Не нашёл, куда распаковать игру: профиль пользователя недоступен "
         L"для записи.");
}

/* ------------------------------------------------------------------- запуск */

/* Хвост своей командной строки — чтобы из exe работали и ключи лаунчера. */
static const wchar_t *own_arguments(void)
{
    const wchar_t *line = GetCommandLineW();
    if (*line == L'"')
    {
        line++;
        while (*line && *line != L'"') line++;
        if (*line == L'"') line++;
    }
    else
    {
        while (*line && *line != L' ') line++;
    }
    while (*line == L' ') line++;
    return line;
}

int WINAPI WinMain(HINSTANCE instance, HINSTANCE previous, LPSTR command, int show)
{
    (void)instance; (void)previous; (void)command; (void)show;

    wchar_t self[MAX_PATH * 2];
    if (!GetModuleFileNameW(NULL, self, MAX_PATH * 2))
        fail(L"Не удалось определить собственный путь.");

    struct payload load;
    if (!read_trailer(self, &load))
        fail(L"Внутри нет вложенных данных — файл собран неправильно.");

    wchar_t base[MAX_PATH * 2];
    choose_base(base, MAX_PATH * 2);

    if (!already_unpacked(base, &load))
        unpack(self, base, &load);

    wchar_t java[MAX_PATH * 2];
    join(java, MAX_PATH * 2, base, L"runtime\\bin\\javaw.exe");
    if (!exists(java))
        fail(L"Java не распакована. Удалите папку W-Factory в профиле "
             L"пользователя и запустите снова.");

    wchar_t jar[MAX_PATH * 2], home[MAX_PATH * 2];
    join(jar, MAX_PATH * 2, base, L"wfactory-launcher.jar");
    join(home, MAX_PATH * 2, base, L"game");

    wchar_t line[MAX_PATH * 8];
    _snwprintf(line, MAX_PATH * 8, L"\"%ls\" -jar \"%ls\" --home \"%ls\" %ls",
               java, jar, home, own_arguments());
    line[MAX_PATH * 8 - 1] = 0;

    STARTUPINFOW startup;
    PROCESS_INFORMATION process;
    ZeroMemory(&startup, sizeof(startup));
    startup.cb = sizeof(startup);
    if (!CreateProcessW(NULL, line, NULL, NULL, FALSE, 0, NULL, base, &startup, &process))
        fail(L"Не удалось запустить лаунчер.");
    CloseHandle(process.hThread);
    CloseHandle(process.hProcess);
    return 0;
}

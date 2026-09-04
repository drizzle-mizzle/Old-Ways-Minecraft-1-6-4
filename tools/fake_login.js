// Фейковый клиент Minecraft 1.6.4: проходит вход целиком, чтобы проверить,
// что пропатченный сервер спрашивает разрешение у нашего сервиса авторизации.
//
// Протокол 78. Последовательность:
//   ->  0x02 Handshake (версия, ник, хост, порт)
//   <-  0xFD Encryption Key Request (serverId, публичный ключ, verify token)
//   ->  HTTP joinserver.jsp  (это делает настоящий клиент; делаем и мы)
//   ->  0xFC Encryption Key Response (RSA-шифрованные секрет и токен)
//   <-  0xFC (пустой) — дальше обе стороны говорят через AES/CFB8
//   ->  0xCD Client Command (0 = войти)  — вот тут сервер и дёргает checkserver.jsp
//   <-  0x01 Login (успех) либо 0xFF Disconnect (причина)

var Socket = Java.type('java.net.Socket');
var DataInputStream = Java.type('java.io.DataInputStream');
var DataOutputStream = Java.type('java.io.DataOutputStream');
var ByteArrayOutputStream = Java.type('java.io.ByteArrayOutputStream');
var CipherInputStream = Java.type('javax.crypto.CipherInputStream');
var CipherOutputStream = Java.type('javax.crypto.CipherOutputStream');
var Cipher = Java.type('javax.crypto.Cipher');
var SecretKeySpec = Java.type('javax.crypto.spec.SecretKeySpec');
var IvParameterSpec = Java.type('javax.crypto.spec.IvParameterSpec');
var KeyFactory = Java.type('java.security.KeyFactory');
var X509KeySpec = Java.type('java.security.spec.X509EncodedKeySpec');
var MessageDigest = Java.type('java.security.MessageDigest');
var SecureRandom = Java.type('java.security.SecureRandom');
var BigInteger = Java.type('java.math.BigInteger');
var ByteArray = Java.type('byte[]');
var URL = Java.type('java.net.URL');
var BufferedReader = Java.type('java.io.BufferedReader');
var InputStreamReader = Java.type('java.io.InputStreamReader');

var HOST = '127.0.0.1', PORT = 25565;
var AUTH = 'http://127.0.0.1:8080';
var USER = $ENV.MC_USER || 'flower';
var PASS = $ENV.MC_PASS || 'flower';

function log(s) { print(s); }

// --- строки протокола: UTF-16BE с длиной в short --------------------------
function writeString(out, s) {
    out.writeShort(s.length);
    for (var i = 0; i < s.length; i++) out.writeChar(s.charCodeAt(i));
}
function readString(inp) {
    // readUnsignedShort, а не readChar: Nashorn отдаёт Java-char как строку,
    // и String.fromCharCode получает NaN — строка молча собирается из нулей.
    var n = inp.readShort(), s = '';
    for (var i = 0; i < n; i++) s += String.fromCharCode(inp.readUnsignedShort());
    return s;
}

// --- HTTP ------------------------------------------------------------------
function httpGet(url) {
    var c = new URL(url).openConnection();
    c.setConnectTimeout(5000); c.setReadTimeout(5000);
    var r = new BufferedReader(new InputStreamReader(c.getInputStream()));
    var line = r.readLine(); r.close();
    return line;
}
function httpPostJson(url, body) {
    var c = new URL(url).openConnection();
    c.setRequestMethod('POST');
    c.setRequestProperty('Content-Type', 'application/json');
    c.setDoOutput(true);
    var bytes = body.getBytes('UTF-8');
    c.getOutputStream().write(bytes); c.getOutputStream().close();
    var r = new BufferedReader(new InputStreamReader(c.getInputStream(), 'UTF-8'));
    var out = '', line;
    while ((line = r.readLine()) !== null) out += line;
    r.close();
    return out;
}

// --- 1. логинимся в сервисе, получаем сессию -------------------------------
log('1. вход в сервис авторизации как ' + USER);
var session = JSON.parse(httpPostJson(AUTH + '/api/login',
    JSON.stringify({ username: USER, password: PASS }))).session;
log('   сессия получена: ' + session.substring(0, 12) + '…');

// --- 2. рукопожатие --------------------------------------------------------
log('2. подключаюсь к серверу ' + HOST + ':' + PORT);
var sock = new Socket(HOST, PORT);
sock.setSoTimeout(15000);
var out = new DataOutputStream(sock.getOutputStream());
var inp = new DataInputStream(sock.getInputStream());

out.writeByte(0x02);
out.writeByte(78);            // версия протокола 1.6.4
writeString(out, USER);
writeString(out, HOST);
out.writeInt(PORT);
out.flush();

var id = inp.readUnsignedByte();
if (id === 0xFF) { log('   сервер отключил: ' + readString(inp)); quit(1); }
if (id !== 0xFD) { log('   неожиданный пакет 0x' + id.toString(16)); quit(1); }

var serverId = readString(inp);
var keyLen = inp.readShort();
var keyBytes = new ByteArray(keyLen); inp.readFully(keyBytes);
var tokLen = inp.readShort();
var token = new ByteArray(tokLen); inp.readFully(token);
log('   0xFD получен, serverId="' + serverId + '", ключ ' + keyLen + ' байт');

// --- 3. общий секрет и хеш сессии -----------------------------------------
var secret = new ByteArray(16);
new SecureRandom().nextBytes(secret);

var pubKey = KeyFactory.getInstance('RSA').generatePublic(new X509KeySpec(keyBytes));
var secretKey = new SecretKeySpec(secret, 'AES');

// Дайджест считаем не своей реализацией, а той же функцией, которой пользуется
// сервер: MinecraftEncryption из его же джарника. Так исключается расхождение
// в порядке update() и в «знаковом» hex — сравниваем яблоки с яблоками.
var mceClass = Java.type('net.minecraft.server.v1_6_R3.MinecraftEncryption').class;
var digestMethod = null;
var methods = mceClass.getDeclaredMethods();
for (var m = 0; m < methods.length; m++) {
    var pt = methods[m].getParameterTypes();
    if (pt.length === 3
        && pt[0].getName() === 'java.lang.String'
        && pt[1].getName() === 'java.security.PublicKey'
        && pt[2].getName() === 'javax.crypto.SecretKey'
        && methods[m].getReturnType().getName() === '[B') {
        digestMethod = methods[m];
        break;
    }
}
if (digestMethod === null) { log('   не нашёл функцию дайджеста в MinecraftEncryption'); quit(1); }
digestMethod.setAccessible(true);
var hash = new BigInteger(digestMethod.invoke(null, serverId, pubKey, secretKey)).toString(16);
log('   дайджест через ' + mceClass.getSimpleName() + '.' + digestMethod.getName()
    + ' -> ' + hash);

// --- 4. клиент сообщает сервису, что заходит ------------------------------
if ($ENV.MC_SKIP_JOIN === '1') {
    log('3. joinserver.jsp НЕ вызываю (проверяем, что сервис преграждает вход)');
} else {
    var joinUrl = AUTH + '/game/joinserver.jsp?user=' + USER
                + '&sessionId=' + session + '&serverId=' + hash;
    log('3. joinserver.jsp ответил: "' + httpGet(joinUrl) + '"');
}

// --- 5. 0xFC и переход на шифрование --------------------------------------
var rsa = Cipher.getInstance('RSA/ECB/PKCS1Padding');
rsa.init(Cipher.ENCRYPT_MODE, pubKey);
var encSecret = rsa.doFinal(secret);
rsa.init(Cipher.ENCRYPT_MODE, pubKey);
var encToken = rsa.doFinal(token);

out.writeByte(0xFC);
out.writeShort(encSecret.length); out.write(encSecret);
out.writeShort(encToken.length);  out.write(encToken);
out.flush();

id = inp.readUnsignedByte();
if (id === 0xFF) { log('   сервер отключил: ' + readString(inp)); quit(1); }
if (id !== 0xFC) { log('   ждали 0xFC, получили 0x' + id.toString(16)); quit(1); }
inp.readShort(); inp.readShort();
log('4. ключ принят, включаю AES/CFB8');

var key = secretKey;
var iv = new IvParameterSpec(secret);
var encCipher = Cipher.getInstance('AES/CFB8/NoPadding');
encCipher.init(Cipher.ENCRYPT_MODE, key, iv);
var decCipher = Cipher.getInstance('AES/CFB8/NoPadding');
decCipher.init(Cipher.DECRYPT_MODE, key, iv);
var eout = new DataOutputStream(new CipherOutputStream(sock.getOutputStream(), encCipher));
var einp = new DataInputStream(new CipherInputStream(sock.getInputStream(), decCipher));

// --- 6. просим вход — здесь сервер идёт в checkserver.jsp -----------------
eout.writeByte(0xCD);
eout.writeByte(0);
eout.flush();
log('5. отправлен 0xCD, сервер должен спросить checkserver.jsp');

id = einp.readUnsignedByte();
if (id === 0xFF) {
    log('\nРЕЗУЛЬТАТ: сервер отклонил вход — "' + readString(einp) + '"');
    sock.close();
    quit(1);
}
if (id === 0x01) {
    var eid = einp.readInt();
    var level = readString(einp);
    var mode = einp.readByte(), dim = einp.readByte(), diff = einp.readByte();
    einp.readByte();
    var maxp = einp.readUnsignedByte();
    log('\nРЕЗУЛЬТАТ: вход разрешён. 0x01 Login: entityId=' + eid
        + ', мир="' + level + '", режим=' + mode + ', измерение=' + dim
        + ', сложность=' + diff + ', максимум игроков=' + maxp);
    sock.close();
    quit(0);
}
log('\nнеожиданный пакет 0x' + id.toString(16));
sock.close();
quit(1);

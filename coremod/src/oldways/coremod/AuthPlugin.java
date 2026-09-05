package oldways.coremod;

import cpw.mods.fml.relauncher.IFMLLoadingPlugin;

import java.util.Map;

/**
 * Точка входа для FML: сообщает, какой трансформер подключить.
 *
 * Зачем вообще coremod. В 1.6.4 адреса авторизации и текстур зашиты в код
 * строками, и до Forge лаунчер правил их прямо в клиентском jar. С Forge так
 * нельзя: FML накладывает на ванильные классы двоичные заплатки и сверяет
 * их с исходником — изменённый jar он отвергнет. Поэтому строки правятся
 * не на диске, а в момент загрузки класса, поверх всех заплаток.
 *
 * Заодно это лечит вторую беду: OptiFine везёт свои копии тех же классов
 * со своими адресами, и патчить пришлось бы каждый мод отдельно.
 */
@IFMLLoadingPlugin.MCVersion("1.6.4")
// Свои классы мимо трансформеров: иначе первый же вызов трансформера тянет
// ClassPatcher через тот же загрузчик, а тот снова зовёт трансформер —
// и загрузка сворачивается в ClassCircularityError.
@IFMLLoadingPlugin.TransformerExclusions({ "oldways." })
public class AuthPlugin implements IFMLLoadingPlugin {

    public String[] getLibraryRequestClass() {
        return null;
    }

    public String[] getASMTransformerClass() {
        return new String[] { "oldways.coremod.AuthTransformer" };
    }

    public String getModContainerClass() {
        return null;
    }

    public String getSetupClass() {
        return null;
    }

    public void injectData(Map<String, Object> data) {
        // ничего не нужно: адрес приходит системным свойством от лаунчера
    }
}

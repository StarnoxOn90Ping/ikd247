package ani.kiwami.settings.saving.internal

import kotlin.reflect.KClass

data class Pref(
    val prefLocation: Location,
    val type: KClass<*>,
    val default: Any
)

enum class Location(val location: String, val exportable: Boolean) {
    General("ani.kiwami.general", true),
    UI("ani.kiwami.ui", true),
    Player("ani.kiwami.player", true),
    Reader("ani.kiwami.reader", true),
    NovelReader("ani.kiwami.novelReader", true),
    Irrelevant("ani.kiwami.irrelevant", false),
    AnimeDownloads("animeDownloads", false),  //different for legacy reasons
    Protected("ani.kiwami.protected", true),
    ExtensionSettings("ani.kiwami.extensionSettings", true);
}

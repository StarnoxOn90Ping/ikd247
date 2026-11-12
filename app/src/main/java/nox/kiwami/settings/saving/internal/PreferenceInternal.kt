package nox.kiwami.settings.saving.internal

import kotlin.reflect.KClass

data class Pref(
    val prefLocation: Location,
    val type: KClass<*>,
    val default: Any
)

enum class Location(val location: String, val exportable: Boolean) {
    General("nox.kiwami.general", true),
    UI("nox.kiwami.ui", true),
    Player("nox.kiwami.player", true),
    Reader("nox.kiwami.reader", true),
    NovelReader("nox.kiwami.novelReader", true),
    Irrelevant("nox.kiwami.irrelevant", false),
    AnimeDownloads("animeDownloads", false),  //different for legacy reasons
    Protected("nox.kiwami.protected", true),
    ExtensionSettings("nox.kiwami.extensionSettings", true);
}

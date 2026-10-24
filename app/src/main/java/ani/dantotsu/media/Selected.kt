package ani.dantotsu.media

import java.io.Serializable

data class Selected(
    var window: Int = 0,
    var recyclerStyle: Int? = null,
    var recyclerReversed: Boolean = false,
    var chip: Int = 0,
    //var source: String = "",
    var sourceIndex: Int = 0,
    /** true once the user (or the auto source search) picked a source for this title; stops the default-extension setting from applying */
    var sourceChosen: Boolean = false,
    var langIndex: Int = 0,
    var preferDub: Boolean = false,
    var server: String? = null,
    var video: Int = 0,
    var latest: Float = 0f,
    var scanlators: List<String>? = null,
) : Serializable

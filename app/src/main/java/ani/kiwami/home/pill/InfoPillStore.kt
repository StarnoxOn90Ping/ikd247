package ani.kiwami.home.pill

import ani.kiwami.notifications.airing.AiringAlarmScheduler
import ani.kiwami.notifications.subscription.SubscriptionHelper
import ani.kiwami.settings.saving.PrefManager
import ani.kiwami.util.Logger
import org.json.JSONArray
import org.json.JSONObject

/**
 * Small on-device caches for the Home pill, so it keeps working in Pre-cached / offline mode:
 *
 *  - the airing times of the anime on the user's list (fetched when online),
 *  - the update times of the planned list (for the backlog card).
 *
 * The existing airing alarms ([AiringAlarmScheduler]) are merged in as a second source of airing
 * times, since they are always stored locally.
 */
object InfoPillStore {

    data class AiringItem(
        val mediaId: Int,
        val name: String,
        val episode: Int,
        /** epoch seconds */
        val airingAtSec: Long
    )

    data class AiringCache(val fetchedAtMs: Long, val idsKey: Int, val items: List<AiringItem>)

    /** Per planned entry: when it was last changed on AniList, in epoch seconds (0 = unknown). */
    data class PlannedSnapshot(
        val savedAtMs: Long,
        val anime: List<Long>,
        val manga: List<Long>
    )

    private const val AIRING_KEY = "pill_airing_v1"
    private const val PLANNED_KEY = "pill_planned_v1"

    // ---- airing -------------------------------------------------------------------------------

    @Synchronized
    fun loadAiring(): AiringCache? {
        val raw = PrefManager.getCustomVal(AIRING_KEY, "")
        if (raw.isBlank()) return null
        return try {
            val o = JSONObject(raw)
            val arr = o.getJSONArray("items")
            val items = (0 until arr.length()).map { i ->
                val e = arr.getJSONObject(i)
                AiringItem(e.getInt("id"), e.getString("name"), e.getInt("ep"), e.getLong("at"))
            }
            AiringCache(o.getLong("fetched"), o.optInt("key"), items)
        } catch (e: Exception) {
            Logger.log(e)
            null
        }
    }

    @Synchronized
    fun saveAiring(cache: AiringCache) {
        try {
            val arr = JSONArray()
            cache.items.forEach {
                arr.put(
                    JSONObject().put("id", it.mediaId).put("name", it.name)
                        .put("ep", it.episode).put("at", it.airingAtSec)
                )
            }
            PrefManager.setCustomVal(
                AIRING_KEY,
                JSONObject().put("fetched", cache.fetchedAtMs).put("key", cache.idsKey)
                    .put("items", arr).toString()
            )
        } catch (e: Exception) {
            Logger.log(e)
        }
    }

    /** Next-episode alarms of subscribed anime. Always local, works offline. */
    fun alarmItems(): List<AiringItem> = try {
        SubscriptionHelper.getSubscriptions().filterValues { it.isAnime }.keys.mapNotNull { id ->
            AiringAlarmScheduler.getAlarm(id)?.let {
                AiringItem(it.mediaId, it.name, it.episode, it.airingAtSec)
            }
        }
    } catch (e: Exception) {
        Logger.log(e)
        emptyList()
    }

    /** Cache + alarms, one entry per (anime, episode). */
    fun allAiring(cached: List<AiringItem>): List<AiringItem> =
        (cached + alarmItems()).distinctBy { it.mediaId to it.episode }

    // ---- planned ------------------------------------------------------------------------------

    @Synchronized
    fun loadPlanned(): PlannedSnapshot? {
        val raw = PrefManager.getCustomVal(PLANNED_KEY, "")
        if (raw.isBlank()) return null
        return try {
            val o = JSONObject(raw)
            fun longs(a: JSONArray) = (0 until a.length()).map { a.getLong(it) }
            PlannedSnapshot(o.getLong("saved"), longs(o.getJSONArray("a")), longs(o.getJSONArray("m")))
        } catch (e: Exception) {
            Logger.log(e)
            null
        }
    }

    @Synchronized
    fun savePlanned(s: PlannedSnapshot) {
        try {
            PrefManager.setCustomVal(
                PLANNED_KEY,
                JSONObject().put("saved", s.savedAtMs)
                    .put("a", JSONArray(s.anime)).put("m", JSONArray(s.manga)).toString()
            )
        } catch (e: Exception) {
            Logger.log(e)
        }
    }
}

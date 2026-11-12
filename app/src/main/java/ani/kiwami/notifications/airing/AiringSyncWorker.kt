package ani.kiwami.notifications.airing

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import ani.kiwami.client
import ani.kiwami.notifications.subscription.SubscriptionHelper
import ani.kiwami.settings.saving.PrefManager
import ani.kiwami.util.Logger
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/**
 * Looks up the next airing episode of one anime on AniList and arms the alarm for it.
 * Retries (WorkManager backoff) while AniList still reports the episode that just aired.
 */
class AiringSyncWorker(appContext: Context, params: WorkerParameters) :
    CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        PrefManager.init(applicationContext)
        val mediaId = inputData.getInt(AiringAlarmScheduler.EXTRA_MEDIA_ID, -1)
        if (mediaId < 0) return Result.success()

        val sub = SubscriptionHelper.getSubscriptions()[mediaId]
        if (sub == null || !sub.isAnime) {
            AiringAlarmScheduler.cancel(applicationContext, mediaId)
            return Result.success()
        }

        return try {
            val next = fetchNextAiring(mediaId)
            val nowSec = System.currentTimeMillis() / 1000
            when {
                next == null -> {
                    // finished airing / nothing announced
                    AiringAlarmScheduler.removeFromStore(mediaId)
                    Result.success()
                }

                next.second <= nowSec + 30 -> {
                    // AniList has not rolled over to the next episode yet
                    if (runAttemptCount < 6) Result.retry() else Result.success()
                }

                else -> {
                    AiringAlarmScheduler.schedule(
                        applicationContext,
                        AiringAlarmScheduler.Alarm(
                            mediaId = mediaId,
                            name = sub.name,
                            image = sub.image,
                            banner = sub.banner,
                            episode = next.first,
                            airingAtSec = next.second
                        )
                    )
                    Result.success()
                }
            }
        } catch (e: Exception) {
            Logger.log(e)
            if (runAttemptCount < 6) Result.retry() else Result.failure()
        }
    }

    /** @return (episode, airingAt seconds) or null when nothing is scheduled */
    private suspend fun fetchNextAiring(mediaId: Int): Pair<Int, Long>? {
        val query = "query(\$id:Int){Media(id:\$id,type:ANIME){nextAiringEpisode{episode airingAt}}}"
        val payload = JSONObject()
            .put("query", query)
            .put("variables", JSONObject().put("id", mediaId))
            .toString()
        val response = client.post(
            "https://graphql.anilist.co/",
            mapOf(
                "Content-Type" to "application/json; charset=utf-8",
                "Accept" to "application/json"
            ),
            requestBody = payload.toRequestBody("application/json; charset=utf-8".toMediaType()),
            cacheTime = 0
        )
        if (response.code !in 200..299) throw Exception("AniList error ${response.code}")
        val next = JSONObject(response.text)
            .optJSONObject("data")
            ?.optJSONObject("Media")
            ?.optJSONObject("nextAiringEpisode")
            ?: return null
        return next.getInt("episode") to next.getLong("airingAt")
    }

    companion object {
        fun workName(mediaId: Int) = "airing_sync_$mediaId"
    }
}

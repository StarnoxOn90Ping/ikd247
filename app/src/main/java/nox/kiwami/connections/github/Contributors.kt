package nox.kiwami.connections.github

import nox.kiwami.client
import nox.kiwami.settings.Developer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

class Contributors {

    suspend fun getContributors(): Array<Developer> {
        val developers = mutableListOf<Developer>()

        try {
            val res = client.get("https://api.github.com/repos/$OWNER/$REPO/contributors")
            val json = Json { ignoreUnknownKeys = true }
            val users: List<GithubUser> = json.decodeFromString(res.text)

            users.filter { !it.login.isNullOrEmpty() && it.type != "Bot" }.forEach { user ->
                val login = user.login ?: return@forEach
                developers.add(
                    Developer(
                        name = login,
                        pfp = user.pfp ?: "https://github.com/$login.png",
                        role = if (login.equals(OWNER, ignoreCase = true)) "Owner & Maintainer" else "Contributor",
                        url = user.url ?: "https://github.com/$login"
                    )
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // Always show the owner, even if GitHub can't be reached or the repo is private
        if (developers.none { it.name.equals(OWNER, ignoreCase = true) }) {
            developers.add(
                0,
                Developer(
                    OWNER,
                    "https://github.com/$OWNER.png",
                    "Owner & Maintainer",
                    "https://github.com/$OWNER"
                )
            )
        }

        // Credit to the project Kiwami is based on
        developers.add(
            Developer(
                "Dantotsu",
                "https://github.com/rebelonion.png",
                "Original project Kiwami is based on, by rebelonion & contributors",
                "https://github.com/rebelonion/Dantotsu"
            )
        )

        return developers.toTypedArray()
    }

    @Serializable
    data class GithubUser(
        val login: String?,
        @SerialName("avatar_url")
        val pfp: String?,
        @SerialName("html_url")
        val url: String?,
        val type: String?,
    )

    companion object {
        private const val OWNER = "StarnoxOn90Ping"
        private const val REPO = "Kiwami"
    }
}

package jetbrains.buildServer.notification.slackNotifier.slack

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.util.Pair
import jetbrains.buildServer.notification.slackNotifier.SlackNotifierProperties
import jetbrains.buildServer.serverSide.TeamCityProperties
import jetbrains.buildServer.serverSide.impl.SecondaryNodeSecurityManager
import jetbrains.buildServer.util.FuncThrow
import jetbrains.buildServer.util.HTTPRequestBuilder
import java.nio.charset.Charset
import java.util.*

class SlackWebApiImpl(
    private val requestHandler: HTTPRequestBuilder.RequestHandler
) : SlackWebApi {
    private val baseUrl = "https://slack.com/api"

    private val logger = Logger.getInstance(SlackWebApi::class.java.name)

    private val mapper = ObjectMapper()
        .registerModule(KotlinModule())
        .configure(SerializationFeature.INDENT_OUTPUT, true)
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

    private val requestTimeout = TeamCityProperties.getInteger(SlackNotifierProperties.requestTimeout, 10_000)

    override fun postMessage(token: String, payload: Message): MaybeMessage {
        val response = request(
            "chat.postMessage",
            token,
            body = mapper.writeValueAsString(payload),
            method = "POST"
        )
        if (response.isException || response.message == null) {
            return MaybeMessage(ok = false, error = unknownError)
        }
        return mapper.readValue(response.message, MaybeMessage::class.java)
    }

    override fun channelsList(token: String, cursor: String?): ChannelsList = readOnlyRequest {
        val response = request(
            "channels.list",
            token,
            listOf(
                Pair("cursor", cursor),
                Pair("limit", "1000"),
                Pair("exclude_members", "true"),
                Pair("exclude_archived", "true")
            )
        )
        if (response.isException || response.message == null) {
            ChannelsList(ok = false, error = unknownError)
        } else {
            mapper.readValue(response.message, ChannelsList::class.java)
        }
    }

    override fun usersList(token: String, cursor: String?): UsersList = readOnlyRequest {
        val response = request(
            "users.list",
            token,
            listOf(
                Pair("cursor", cursor),
                Pair("limit", "1000")
            )
        )
        if (response.isException || response.message == null) {
            UsersList(ok = false, error = unknownError)
        } else {
            mapper.readValue(response.message, UsersList::class.java)
        }
    }

    override fun authTest(token: String): AuthTestResult = readOnlyRequest {
        val response = request("auth.test", token)

        if (response.isException || response.message == null) {
            AuthTestResult(ok = false, error = unknownError)
        } else {
            mapper.readValue(response.message, AuthTestResult::class.java)
        }
    }

    override fun botsInfo(token: String, botId: String): MaybeBot = readOnlyRequest {
        val response = request("bots.info", token, parameters = listOf(Pair("bot", botId)))

        if (response.isException || response.message == null) {
            MaybeBot(ok = false, error = unknownError)
        } else {
            mapper.readValue(response.message, MaybeBot::class.java)
        }
    }

    override fun conversationsMembers(token: String, channelId: String): ConversationMembers = readOnlyRequest {
        val response = request("conversations.members", token, parameters = listOf(Pair("channel", channelId)))

        if (response.isException || response.message == null) {
            ConversationMembers(ok = false, error = unknownError)
        } else {
            mapper.readValue(response.message, ConversationMembers::class.java)
        }
    }

    override fun usersIdentity(token: String): UserIdentity = readOnlyRequest {
        val response = request("users.identity", token)
        if (response.isException || response.message == null) {
            UserIdentity(ok = false, error = unknownError)
        } else {
            mapper.readValue(response.message, UserIdentity::class.java)
        }
    }

    override fun usersInfo(token: String, userId: String): MaybeUser = readOnlyRequest {
        val response = request("users.info", token, listOf(Pair("user", userId)))
        if (response.isException || response.message == null) {
            MaybeUser(ok = false, error = unknownError)
        } else {
            mapper.readValue(response.message, MaybeUser::class.java)
        }
    }

    override fun oauthAccess(
            clientId: String,
            clientSecret: String,
            code: String,
            redirectUrl: String
    ): OauthAccessToken {
        val encodedSecret = Base64.getEncoder().encodeToString("$clientId:$clientSecret".toByteArray())

        val response = request(
                "oauth.access",
                "",
            parameters = listOf(
                Pair("code", code),
                Pair("redirect_uri", redirectUrl)
            ),
                headers = mapOf(
                        "Authorization" to "Basic $encodedSecret"
                )
        )

        if (response.isException || response.message == null) {
            return OauthAccessToken(ok = false, error = unknownError)
        }

        return mapper.readValue(response.message, OauthAccessToken::class.java)
    }

    private fun request(
            path: String,
            token: String,
            parameters: List<Pair<String, String?>> = mutableListOf(),
            headers: Map<String, String> = emptyMap(),
            body: String = "",
            method: String = "GET"
    ): SlackResponse {
        var response: String? = null
        var isException = false

        val post: HTTPRequestBuilder.Request =
            HTTPRequestBuilder("$baseUrl/${path}")
                .withMethod(method)
                .withPostStringEntity(
                    body,
                    "application/json",
                    Charset.forName("UTF-8")
                )
                .withHeader("Authorization", "Bearer $token")
                .withHeader("Content-Type", "application/json")
                .also {
                    for (header in headers) {
                        it.withHeader(header.key, header.value)
                    }
                }
                .withTimeout(requestTimeout)
                .addParameters(parameters)
                .withRetryCount(2)
                .onErrorResponse(HTTPRequestBuilder.ResponseConsumer {
                    response = it.bodyAsString
                })
                .onSuccess {
                    response = it.bodyAsString
                }
                .onException {
                    logger.error(
                        "Exception occurred when sending request to Slack: ${it.message}"
                    )
                    isException = true
                }
                .build()

        requestHandler.doRequest(post)

        return SlackResponse(response, isException)
    }

    private fun <T> readOnlyRequest(block: () -> T): T =
        SecondaryNodeSecurityManager.runSafeNetworkOperation(FuncThrow<T, Throwable> {
            block()
        })

    internal data class SlackResponse(val message: String?, val isException: Boolean)

    companion object {
        const val unknownError = "Unknown error. See notification logs for more details"
    }
}
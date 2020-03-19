package jetbrains.buildServer.notification

import jetbrains.buildServer.BuildProblemData
import jetbrains.buildServer.ExtensionHolder
import jetbrains.buildServer.messages.DefaultMessagesInfo
import jetbrains.buildServer.notification.slackNotifier.*
import jetbrains.buildServer.serverSide.MockServerPluginDescriptior
import jetbrains.buildServer.serverSide.SBuild
import jetbrains.buildServer.serverSide.impl.NotificationRulesConstants
import jetbrains.buildServer.serverSide.oauth.OAuthConnectionsManager
import jetbrains.buildServer.users.SUser
import org.testng.annotations.BeforeMethod
import java.util.function.BooleanSupplier

open class BaseSlackTestCase : BaseNotificationRulesTestCase() {
    private lateinit var mySlackApi: MockSlackWebApi
    private lateinit var myDescriptor: SlackNotifierDescriptor
    private lateinit var myNotifier: SlackNotifier
    private lateinit var myUser: SUser
    private lateinit var myAssignerUser: SUser

    @BeforeMethod(alwaysRun = true)
    override fun setUp() {
        super.setUp()

        setInternalProperty("teamcity.notifications.quiet.period.seconds", "0")
        myFixture.notificationProcessor.setBuildFailingDelay(700)
        myFixture.notificationProcessor.setCheckHangedBuildsInterval(50)

        val connectionManager = OAuthConnectionsManager(myFixture.getSingletonService(ExtensionHolder::class.java))

        val slackApiFactory = MockSlackWebApiFactory()
        mySlackApi = slackApiFactory.createSlackWebApi()

        myDescriptor = SlackNotifierDescriptor(
            MockServerPluginDescriptior()
        )
        myNotifier = SlackNotifier(
            myFixture.notificatorRegistry,
            slackApiFactory,
            SimpleMessageBuilder(
                SlackMessageFormatter(),
                myFixture.webLinks,
                myProjectManager
            ),
            myFixture.serverPaths,
            myProjectManager,
            connectionManager,
            myDescriptor
        )

        myFixture.addService(myDescriptor)
        myFixture.addService(myNotifier)

        connectionManager.addConnection(
            myProject,
            SlackConnection.type,
            mapOf("externalId" to "test_connection", "secure:token" to "test_token")
        )

        myUser = createUser("test_user")
        myUser.setUserProperty(myDescriptor.channelProperty, "#test_channel")
        myUser.setUserProperty(myDescriptor.connectionProperty, "test_connection")
        makeProjectAccessible(myUser, myProject.projectId)

        myAssignerUser = createUser("investigation_assigner")
        makeProjectAccessible(myUser, myProject.projectId)
    }

    fun `given user is subscribed to`(vararg events: NotificationRule.Event) {
        storeRules(myUser, myNotifier, newRule(*events))
    }

    fun `given build feature is subscribed to`(vararg events: NotificationRule.Event) {
        myBuildType.addBuildFeature(
            FeatureProviderNotificationRulesHolder.FEATURE_NAME,
            mapOf(
                "notifier" to myNotifier.notificatorType,
                myDescriptor.channelProperty.key to "#test_channel",
                myDescriptor.connectionProperty.key to "test_connection",
                *(events.map { NotificationRulesConstants.getName(it) to "true" }).toTypedArray()
            )
        )
    }

    fun `when build starts`(): SBuild = startBuild()
    fun `when build finishes`(): SBuild {
        startBuild()
        return finishBuild()
    }
    fun `when build fails`(): SBuild {
        return createFailedBuild()
    }
    fun `when build newly fails`(): SBuild {
        startBuild()
        finishBuild()
        return createFailedBuild()
    }
    fun `when build is failing`(): SBuild {
        val build = startBuild()

        myFixture.logBuildMessages(
            build,
            listOf(
                DefaultMessagesInfo.createBlockStart(
                    "test1",
                    DefaultMessagesInfo.BLOCK_TYPE_TEST
                )
            )
        )
        myFixture.logBuildMessages(
            build,
            listOf(
                DefaultMessagesInfo.createTestFailure(
                    "test1",
                    Exception("test1 failed")
                )
            )
        )

        myFixture.logBuildMessages(
            build,
            listOf(
                DefaultMessagesInfo.createBlockEnd(
                    "test1",
                    DefaultMessagesInfo.BLOCK_TYPE_TEST
                )
            )
        )


        Thread.sleep(1500)

        return build
    }
    fun `when build hangs`(): SBuild {
        val build = startBuild()
        makeProjectAccessible(myUser, myBuildType.projectId)
        makeBuildHanging(build)
        return build
    }

    fun `when build problem occurs`(): SBuild {
        return build().`in`(myBuildType).withProblem(
            BuildProblemData.createBuildProblem("1", "TC_COMPILATION_ERROR_TYPE", "compilation error")
        ).finish()
    }

    fun `when responsibility changes`(): SUser {
        myBuildType.setResponsible(myUser, "will fix", myAssignerUser)
        return myUser
    }

    fun `then message should contain`(vararg strs: String) {
        waitForAssert(BooleanSupplier {
            mySlackApi.messages.isNotEmpty()
        }, 2000L)

        for (str in strs) {
            assertContains(mySlackApi.messages.last().text, str)
        }
    }

    fun `then no messages should be sent`() {
        assertEmpty(mySlackApi.messages)
    }

    infix fun <T> T.And(other: T): List<T> {
        return listOf(this, other)
    }

    infix fun <T> List<T>.And(other: T): List<T> {
        return this + other
    }

    fun Int.`messages should be sent`() {
        waitForAssert(BooleanSupplier {
            mySlackApi.messages.size == this
        }, 2000L)
    }

    private fun <T> Iterable<T>.last(): T {
        return reversed().first()
    }
}
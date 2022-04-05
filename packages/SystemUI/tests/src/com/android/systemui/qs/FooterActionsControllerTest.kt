package com.android.systemui.qs

import android.os.Handler
import android.os.UserManager
import android.provider.Settings
import android.testing.AndroidTestingRunner
import android.testing.TestableLooper
import android.testing.ViewUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.test.filters.SmallTest
import com.android.internal.logging.MetricsLogger
import com.android.internal.logging.UiEventLogger
import com.android.internal.logging.testing.FakeMetricsLogger
import com.android.systemui.R
import com.android.systemui.classifier.FalsingManagerFake
import com.android.systemui.globalactions.GlobalActionsDialogLite
import com.android.systemui.plugins.ActivityStarter
import com.android.systemui.settings.UserTracker
import com.android.systemui.statusbar.phone.MultiUserSwitchController
import com.android.systemui.statusbar.policy.DeviceProvisionedController
import com.android.systemui.statusbar.policy.UserInfoController
import com.android.systemui.util.mockito.capture
import com.android.systemui.util.settings.FakeSettings
import com.android.systemui.utils.leaks.LeakCheckedTest
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyBoolean
import org.mockito.Captor
import org.mockito.Mock
import org.mockito.Mockito
import org.mockito.Mockito.atLeastOnce
import org.mockito.Mockito.clearInvocations
import org.mockito.Mockito.never
import org.mockito.Mockito.reset
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations
import javax.inject.Provider
import org.mockito.Mockito.`when` as whenever

@SmallTest
@TestableLooper.RunWithLooper(setAsMainLooper = true)
@RunWith(AndroidTestingRunner::class)
class FooterActionsControllerTest : LeakCheckedTest() {
    @Mock
    private lateinit var userManager: UserManager
    @Mock
    private lateinit var userTracker: UserTracker
    @Mock
    private lateinit var activityStarter: ActivityStarter
    @Mock
    private lateinit var deviceProvisionedController: DeviceProvisionedController
    @Mock
    private lateinit var userInfoController: UserInfoController
    @Mock
    private lateinit var multiUserSwitchControllerFactory: MultiUserSwitchController.Factory
    @Mock
    private lateinit var multiUserSwitchController: MultiUserSwitchController
    @Mock
    private lateinit var globalActionsDialogProvider: Provider<GlobalActionsDialogLite>
    @Mock
    private lateinit var globalActionsDialog: GlobalActionsDialogLite
    @Mock
    private lateinit var uiEventLogger: UiEventLogger
    @Mock
    private lateinit var securityFooterController: QSSecurityFooter
    @Mock
    private lateinit var fgsManagerController: QSFgsManagerFooter
    @Captor
    private lateinit var visibilityChangedCaptor:
        ArgumentCaptor<VisibilityChangedDispatcher.OnVisibilityChangedListener>

    private lateinit var controller: FooterActionsController

    private val metricsLogger: MetricsLogger = FakeMetricsLogger()
    private lateinit var view: FooterActionsView
    private val falsingManager: FalsingManagerFake = FalsingManagerFake()
    private lateinit var testableLooper: TestableLooper
    private lateinit var fakeSettings: FakeSettings
    private lateinit var securityFooter: View
    private lateinit var fgsFooter: View

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)
        testableLooper = TestableLooper.get(this)
        fakeSettings = FakeSettings()

        whenever(multiUserSwitchControllerFactory.create(any()))
                .thenReturn(multiUserSwitchController)
        whenever(globalActionsDialogProvider.get()).thenReturn(globalActionsDialog)

        securityFooter = View(mContext)
        fgsFooter = View(mContext)

        whenever(securityFooterController.view).thenReturn(securityFooter)
        whenever(fgsManagerController.view).thenReturn(fgsFooter)

        view = inflateView()

        controller = constructFooterActionsController(view)
        controller.init()
        ViewUtils.attachView(view)
        // View looper is the testable looper associated with the test
        testableLooper.processAllMessages()
    }

    @After
    fun tearDown() {
        if (view.isAttachedToWindow) {
            ViewUtils.detachView(view)
        }
    }

    @Test
    fun testInitializesControllers() {
        verify(multiUserSwitchController).init()
        verify(fgsManagerController).init()
        verify(securityFooterController).init()
    }

    @Test
    fun testLogPowerMenuClick() {
        controller.visible = true
        falsingManager.setFalseTap(false)

        view.findViewById<View>(R.id.pm_lite).performClick()
        // Verify clicks are logged
        verify(uiEventLogger, Mockito.times(1))
                .log(GlobalActionsDialogLite.GlobalActionsEvent.GA_OPEN_QS)
    }

    @Test
    fun testSettings_UserNotSetup() {
        whenever(deviceProvisionedController.isCurrentUserSetup).thenReturn(false)
        view.findViewById<View>(R.id.settings_button).performClick()
        // Verify Settings wasn't launched.
        verify<ActivityStarter>(activityStarter, Mockito.never()).startActivity(any(), anyBoolean())
    }

    @Test
    fun testMultiUserSwitchUpdatedWhenExpansionStarts() {
        // When expansion starts, listening is set to true
        val multiUserSwitch = view.requireViewById<View>(R.id.multi_user_switch)

        assertThat(multiUserSwitch.visibility).isNotEqualTo(View.VISIBLE)

        whenever(multiUserSwitchController.isMultiUserEnabled).thenReturn(true)

        controller.setListening(true)
        testableLooper.processAllMessages()

        assertThat(multiUserSwitch.visibility).isEqualTo(View.VISIBLE)
    }

    @Test
    fun testMultiUserSwitchUpdatedWhenSettingChanged() {
        // Always listening to setting while View is attached
        testableLooper.processAllMessages()

        val multiUserSwitch = view.requireViewById<View>(R.id.multi_user_switch)
        assertThat(multiUserSwitch.visibility).isNotEqualTo(View.VISIBLE)

        // The setting is only used as an indicator for whether the view should refresh. The actual
        // value of the setting is ignored; isMultiUserEnabled is the source of truth
        whenever(multiUserSwitchController.isMultiUserEnabled).thenReturn(true)

        // Changing the value of USER_SWITCHER_ENABLED should cause the view to update
        fakeSettings.putIntForUser(Settings.Global.USER_SWITCHER_ENABLED, 1, userTracker.userId)
        testableLooper.processAllMessages()

        assertThat(multiUserSwitch.visibility).isEqualTo(View.VISIBLE)
    }

    @Test
    fun testMultiUserSettingNotListenedAfterDetach() {
        testableLooper.processAllMessages()

        val multiUserSwitch = view.requireViewById<View>(R.id.multi_user_switch)
        assertThat(multiUserSwitch.visibility).isNotEqualTo(View.VISIBLE)

        ViewUtils.detachView(view)

        // The setting is only used as an indicator for whether the view should refresh. The actual
        // value of the setting is ignored; isMultiUserEnabled is the source of truth
        whenever(multiUserSwitchController.isMultiUserEnabled).thenReturn(true)

        // Changing the value of USER_SWITCHER_ENABLED should cause the view to update
        fakeSettings.putIntForUser(Settings.Global.USER_SWITCHER_ENABLED, 1, userTracker.userId)
        testableLooper.processAllMessages()

        assertThat(multiUserSwitch.visibility).isNotEqualTo(View.VISIBLE)
    }

    @Test
    fun testCleanUpGAD() {
        reset(globalActionsDialogProvider)
        // We are creating a new controller, so detach the views from it
        (securityFooter.parent as ViewGroup).removeView(securityFooter)
        (fgsFooter.parent as ViewGroup).removeView(fgsFooter)

        whenever(globalActionsDialogProvider.get()).thenReturn(globalActionsDialog)
        val view = inflateView()
        controller = constructFooterActionsController(view)
        controller.init()
        verify(globalActionsDialogProvider, never()).get()

        // GAD is constructed during attachment
        ViewUtils.attachView(view)
        testableLooper.processAllMessages()
        verify(globalActionsDialogProvider).get()

        ViewUtils.detachView(view)
        testableLooper.processAllMessages()
        verify(globalActionsDialog).destroy()
    }

    @Test
    fun testSeparatorVisibility_noneVisible_gone() {
        verify(securityFooterController)
            .setOnVisibilityChangedListener(capture(visibilityChangedCaptor))
        val listener = visibilityChangedCaptor.value
        val separator = controller.securityFootersSeparator

        setVisibilities(securityFooterVisible = false, fgsFooterVisible = false, listener)
        assertThat(separator.visibility).isEqualTo(View.GONE)
    }

    @Test
    fun testSeparatorVisibility_onlySecurityFooterVisible_gone() {
        verify(securityFooterController)
            .setOnVisibilityChangedListener(capture(visibilityChangedCaptor))
        val listener = visibilityChangedCaptor.value
        val separator = controller.securityFootersSeparator

        setVisibilities(securityFooterVisible = true, fgsFooterVisible = false, listener)
        assertThat(separator.visibility).isEqualTo(View.GONE)
    }

    @Test
    fun testSeparatorVisibility_onlyFgsFooterVisible_gone() {
        verify(securityFooterController)
            .setOnVisibilityChangedListener(capture(visibilityChangedCaptor))
        val listener = visibilityChangedCaptor.value
        val separator = controller.securityFootersSeparator

        setVisibilities(securityFooterVisible = false, fgsFooterVisible = true, listener)
        assertThat(separator.visibility).isEqualTo(View.GONE)
    }

    @Test
    fun testSeparatorVisibility_bothVisible_visible() {
        verify(securityFooterController)
            .setOnVisibilityChangedListener(capture(visibilityChangedCaptor))
        val listener = visibilityChangedCaptor.value
        val separator = controller.securityFootersSeparator

        setVisibilities(securityFooterVisible = true, fgsFooterVisible = true, listener)
        assertThat(separator.visibility).isEqualTo(View.VISIBLE)
    }

    @Test
    fun testFgsFooterCollapsed() {
        verify(securityFooterController)
            .setOnVisibilityChangedListener(capture(visibilityChangedCaptor))
        val listener = visibilityChangedCaptor.value

        val booleanCaptor = ArgumentCaptor.forClass(Boolean::class.java)

        clearInvocations(fgsManagerController)
        setVisibilities(securityFooterVisible = false, fgsFooterVisible = true, listener)
        verify(fgsManagerController, atLeastOnce()).setCollapsed(capture(booleanCaptor))
        assertThat(booleanCaptor.allValues.last()).isFalse()

        clearInvocations(fgsManagerController)
        setVisibilities(securityFooterVisible = true, fgsFooterVisible = true, listener)
        verify(fgsManagerController, atLeastOnce()).setCollapsed(capture(booleanCaptor))
        assertThat(booleanCaptor.allValues.last()).isTrue()
    }

    private fun setVisibilities(
        securityFooterVisible: Boolean,
        fgsFooterVisible: Boolean,
        listener: VisibilityChangedDispatcher.OnVisibilityChangedListener
    ) {
        securityFooter.visibility = if (securityFooterVisible) View.VISIBLE else View.GONE
        listener.onVisibilityChanged(securityFooter.visibility)
        fgsFooter.visibility = if (fgsFooterVisible) View.VISIBLE else View.GONE
        listener.onVisibilityChanged(fgsFooter.visibility)
    }

    private fun inflateView(): FooterActionsView {
        return LayoutInflater.from(context)
                .inflate(R.layout.footer_actions, null) as FooterActionsView
    }

    private fun constructFooterActionsController(view: FooterActionsView): FooterActionsController {
        return FooterActionsController(view, multiUserSwitchControllerFactory,
                activityStarter, userManager, userTracker, userInfoController,
                deviceProvisionedController, securityFooterController, fgsManagerController,
                falsingManager, metricsLogger, globalActionsDialogProvider, uiEventLogger,
                showPMLiteButton = true, fakeSettings, Handler(testableLooper.looper))
    }
}
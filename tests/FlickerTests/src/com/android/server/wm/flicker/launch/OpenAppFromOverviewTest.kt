/*
 * Copyright (C) 2020 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.server.wm.flicker.launch

import android.platform.test.annotations.FlakyTest
import android.platform.test.annotations.Presubmit
import android.platform.test.annotations.RequiresDevice
import android.view.Surface
import com.android.server.wm.flicker.FlickerParametersRunnerFactory
import com.android.server.wm.flicker.FlickerTestParameter
import com.android.server.wm.flicker.FlickerTestParameterFactory
import com.android.server.wm.flicker.annotation.FlickerServiceCompatible
import com.android.server.wm.flicker.annotation.Group1
import com.android.server.wm.flicker.dsl.FlickerBuilder
import com.android.server.wm.flicker.helpers.setRotation
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import org.junit.runners.Parameterized

/**
 * Test launching an app from the recents app view (the overview)
 *
 * To run this test: `atest FlickerTests:OpenAppFromOverviewTest`
 *
 * Actions:
 *     Launch [testApp]
 *     Press recents
 *     Relaunch an app [testApp] by selecting it in the overview screen, and wait animation to
 *     complete (only this action is traced)
 *
 * Notes:
 *     1. Some default assertions (e.g., nav bar, status bar and screen covered)
 *        are inherited [OpenAppTransition]
 *     2. Part of the test setup occurs automatically via
 *        [com.android.server.wm.flicker.TransitionRunnerWithRules],
 *        including configuring navigation mode, initial orientation and ensuring no
 *        apps are running before setup
 */
@RequiresDevice
@FlickerServiceCompatible
@RunWith(Parameterized::class)
@Parameterized.UseParametersRunnerFactory(FlickerParametersRunnerFactory::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
@Group1
open class OpenAppFromOverviewTest(
    testSpec: FlickerTestParameter
) : OpenAppFromLauncherTransition(testSpec) {

    /**
     * Defines the transition used to run the test
     */
    override val transition: FlickerBuilder.() -> Unit
        get() = {
            super.transition(this)
            setup {
                test {
                    tapl.setExpectedRotationCheckEnabled(false)
                    testApp.launchViaIntent(wmHelper)
                }
                eachRun {
                    tapl.goHome()
                    wmHelper.StateSyncBuilder()
                        .withHomeActivityVisible()
                        .waitForAndVerify()
                    // By default, launcher doesn't rotate on phones, but rotates on tablets
                    if (testSpec.isTablet) {
                        tapl.setExpectedRotation(testSpec.startRotation)
                    } else {
                        tapl.setExpectedRotation(Surface.ROTATION_0)
                    }
                    tapl.workspace.switchToOverview()
                    wmHelper.StateSyncBuilder()
                        .withRecentsActivityVisible()
                        .waitForAndVerify()
                    this.setRotation(testSpec.startRotation)
                }
            }
            transitions {
                tapl.overview.currentTask.open()
                wmHelper.StateSyncBuilder()
                    .withFullScreenApp(testApp)
                    .waitForAndVerify()
            }
        }

    /** {@inheritDoc} */
    @Presubmit
    @Test
    override fun appLayerReplacesLauncher() = super.appLayerReplacesLauncher()

    /** {@inheritDoc} */
    @FlakyTest
    @Test
    override fun navBarLayerPositionAtStartAndEnd() = super.navBarLayerPositionAtStartAndEnd()

    /** {@inheritDoc} */
    @Presubmit
    @Test
    override fun appLayerBecomesVisible() = super.appLayerBecomesVisible_warmStart()

    /** {@inheritDoc} */
    @Presubmit
    @Test
    override fun appWindowBecomesVisible() = super.appWindowBecomesVisible_warmStart()

    companion object {
        /**
         * Creates the test configurations.
         *
         * See [FlickerTestParameterFactory.getConfigNonRotationTests] for configuring
         * repetitions, screen orientation and navigation modes.
         */
        @Parameterized.Parameters(name = "{0}")
        @JvmStatic
        fun getParams(): Collection<FlickerTestParameter> {
            return FlickerTestParameterFactory.getInstance()
                .getConfigNonRotationTests()
        }
    }
}

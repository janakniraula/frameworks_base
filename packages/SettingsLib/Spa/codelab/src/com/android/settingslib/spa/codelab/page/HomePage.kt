/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.settingslib.spa.codelab.page

import android.os.Bundle
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import com.android.settingslib.spa.codelab.R
import com.android.settingslib.spa.framework.api.SettingsPageProvider
import com.android.settingslib.spa.framework.theme.SettingsDimension
import com.android.settingslib.spa.framework.theme.SettingsTheme

object HomePageProvider : SettingsPageProvider {
    override val name = Destinations.Home

    @Composable
    override fun Page(arguments: Bundle?) {
        HomePage()
    }
}

@Composable
private fun HomePage() {
    Column {
        Text(
            text = stringResource(R.string.app_name),
            modifier = Modifier.padding(SettingsDimension.itemPadding),
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.headlineMedium,
        )

        PreferencePageProvider.EntryItem()

        ArgumentPageProvider.EntryItem(stringParam = "foo", intParam = 0)
    }
}

@Preview(showBackground = true)
@Composable
private fun HomeScreenPreview() {
    SettingsTheme {
        HomePage()
    }
}

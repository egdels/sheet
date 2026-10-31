// Copyright (C) 2026 Christian Kierdorf
// SPDX-License-Identifier: GPL-3.0-or-later

package de.schliweb.sheet

import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import de.schliweb.sheet.ui.SheetScreen

class MainActivity : ComponentActivity() {
    private val vm: SheetViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { SheetScreen(vm) }
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        vm.onTrimMemory()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if ((keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == KeyEvent.KEYCODE_VOLUME_UP) && event.repeatCount == 0 && vm.onShutterKey()) return true
        return super.onKeyDown(keyCode, event)
    }
}

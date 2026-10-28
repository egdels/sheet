// Copyright (C) 2026 Christian Kierdorf
// SPDX-License-Identifier: GPL-3.0-or-later

package de.schliweb.sheet.ui

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.AddAPhoto
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.RotateRight
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import de.schliweb.sheet.R
import de.schliweb.sheet.SheetViewModel
import de.schliweb.sheet.core.document.Document

private val SIDE_PANEL = 260.dp
private val GRID_PADDING = 16.dp

private val TILE_BUTTONS = 48.dp

@Composable
fun PagesScreen(vm: SheetViewModel) {
    val document by vm.document.collectAsState()
    val reading by vm.reading.collectAsState()
    val busy by vm.busy.collectAsState()
    val folder by vm.folder.collectAsState()
    var confirmDiscard by rememberSaveable { mutableStateOf(false) }
    val share by vm.share.collectAsState()
    val context = LocalContext.current
    val shareTitle = stringResource(R.string.share)
    LaunchedEffect(share) {
        val uri = share ?: return@LaunchedEffect
        val send = Intent(Intent.ACTION_SEND).setType("application/pdf").putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(Intent.createChooser(send, shareTitle))
        vm.shareShown()
    }
    val pickAndSave = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { it?.let(vm::pickFolderAndSave) }
    val pickOnly = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { it?.let(vm::pickFolder) }
    val discard: @Composable () -> Unit = {
        IconButton(onClick = { confirmDiscard = true }, enabled = document.pages.isNotEmpty() && !busy) {
            Icon(Icons.Outlined.DeleteSweep, stringResource(R.string.discard))
        }
    }
    val title: @Composable (Boolean) -> Unit = { withDiscard ->
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, top = 8.dp, end = 4.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                pluralStringResource(R.plurals.pages, document.pages.size, document.pages.size),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f).padding(vertical = 8.dp),
            )
            if (withDiscard) discard()
        }
    }
    val grid: @Composable (Modifier, GridCells) -> Unit = { modifier, columns ->
        LazyVerticalGrid(
            columns = columns,
            modifier = modifier,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(GRID_PADDING),
        ) {
            itemsIndexed(document.pages, key = { _, p -> p.id }) { index, page ->
                PageCard(vm, page, index, document.pages.size, page.id in reading, enabled = !busy)
            }
        }
    }
    val status: @Composable () -> Unit = {
        if (busy && reading.isNotEmpty()) {
            Text(
                stringResource(R.string.waiting_for_text),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }
        folder?.let { (_, name) ->
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.saves_to, name), style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                TextButton(onClick = { pickOnly.launch(null) }, enabled = !busy) { Text(stringResource(R.string.change_folder)) }
            }
        }
    }
    val actions: @Composable () -> Unit = {
        IconButton(onClick = vm::backToCamera, enabled = !busy) {
            Icon(Icons.Outlined.AddAPhoto, stringResource(R.string.add_page))
        }
        IconButton(onClick = vm::sharePdf, enabled = !busy && document.pages.isNotEmpty()) {
            Icon(Icons.Outlined.Share, stringResource(R.string.share))
        }
    }
    val save: @Composable (Modifier) -> Unit = { modifier ->
        if (busy) {
            CircularProgressIndicator(Modifier.padding(horizontal = 24.dp))
        } else {
            Button(
                onClick = { if (folder == null) pickAndSave.launch(null) else vm.savePdf() },
                enabled = document.pages.isNotEmpty(),
                modifier = modifier,
            ) { Text(stringResource(R.string.save_pdf)) }
        }
    }
    if (isLandscape()) {
        Row(Modifier.fillMaxSize().safeDrawingPadding()) {
            Column(Modifier.weight(1f)) {
                title(false)
                BoxWithConstraints(Modifier.weight(1f)) {
                    val tile = ((maxHeight - GRID_PADDING * 2 - TILE_BUTTONS) * 0.75f).coerceIn(96.dp, 200.dp)
                    grid(Modifier.fillMaxSize(), GridCells.FixedSize(tile))
                }
            }
            Column(
                Modifier.fillMaxHeight().width(SIDE_PANEL).padding(top = 8.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.Bottom),
            ) {
                Box(Modifier.weight(1f).fillMaxWidth().padding(end = 4.dp), contentAlignment = Alignment.TopEnd) { discard() }
                status()
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) { actions() }
                save(Modifier.fillMaxWidth().padding(horizontal = 16.dp))
            }
        }
    } else {
        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            title(true)
            grid(Modifier.weight(1f), GridCells.Adaptive(160.dp))
            status()
            Row(
                Modifier.fillMaxWidth().padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                actions()
                save(Modifier)
            }
        }
    }
    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            text = { Text(pluralStringResource(R.plurals.discard_question, document.pages.size, document.pages.size)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDiscard = false
                    vm.discardDocument()
                }) { Text(stringResource(R.string.discard), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { OutlinedButton(onClick = { confirmDiscard = false }) { Text(stringResource(R.string.keep)) } },
        )
    }
}

@Composable
private fun PageCard(vm: SheetViewModel, page: Document.Page, index: Int, count: Int, reading: Boolean, enabled: Boolean) {
    var thumb by remember { mutableStateOf<ImageBitmap?>(null) }
    val enhanced by vm.enhanced.collectAsState()
    LaunchedEffect(page, enhanced) { thumb = vm.thumbnail(page)?.asImageBitmap() }
    Column {
        Box(Modifier.fillMaxWidth().aspectRatio(0.75f), contentAlignment = Alignment.BottomCenter) {
            val editLabel = stringResource(R.string.edit_corners)
            val paper = RoundedCornerShape(2.dp)
            Box(
                Modifier
                    .aspectRatio(thumb?.let { it.width.toFloat() / it.height } ?: 0.75f)
                    .shadow(3.dp, paper)
                    .background(Color.White, paper)
                    .clickable(enabled = enabled, onClickLabel = editLabel) { vm.editPage(page.id) },
            ) {
                thumb?.let { Image(it, contentDescription = stringResource(R.string.page_number, index + 1), contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize()) }
                Text(
                    "${index + 1}",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.align(Alignment.TopStart).padding(6.dp).background(MaterialTheme.colorScheme.primary, RoundedCornerShape(4.dp)).padding(horizontal = 6.dp, vertical = 2.dp),
                )
                if (reading) {
                    Column(
                        Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Color.Black.copy(alpha = 0.55f)).padding(6.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        CircularProgressIndicator(Modifier.padding(2.dp), color = Color.White, strokeWidth = 2.dp)
                        Text(stringResource(R.string.reading_text), color = Color.White, style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
        CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurfaceVariant) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                IconButton(onClick = { vm.movePage(page.id, index - 1) }, enabled = enabled && index > 0) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.move_earlier))
                }
                IconButton(onClick = { vm.movePage(page.id, index + 1) }, enabled = enabled && index < count - 1) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowForward, stringResource(R.string.move_later))
                }
                IconButton(onClick = { vm.turnPage(page.id) }, enabled = enabled) {
                    Icon(Icons.Outlined.RotateRight, stringResource(R.string.turn))
                }
                IconButton(onClick = { vm.deletePage(page.id) }, enabled = enabled) {
                    Icon(Icons.Outlined.Delete, stringResource(R.string.delete_page))
                }
            }
        }
    }
}

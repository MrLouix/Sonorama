package io.github.mrlouix.sonorama.ui.main

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.mrlouix.sonorama.R
import io.github.mrlouix.sonorama.audio.MixMode
import io.github.mrlouix.sonorama.audio.MixState
import io.github.mrlouix.sonorama.data.Library
import io.github.mrlouix.sonorama.data.Limits
import io.github.mrlouix.sonorama.data.Page
import io.github.mrlouix.sonorama.data.PageExchange
import io.github.mrlouix.sonorama.ui.MainViewModel
import io.github.mrlouix.sonorama.ui.Screen
import io.github.mrlouix.sonorama.ui.Symbols
import io.github.mrlouix.sonorama.ui.text
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(viewModel: MainViewModel, snackbar: SnackbarHostState) {
    val library by viewModel.library.collectAsStateWithLifecycle()
    val mix by viewModel.mix.collectAsStateWithLifecycle()
    val pageToShow by viewModel.pageToShow.collectAsStateWithLifecycle()
    val pages = library.pages
    val scope = rememberCoroutineScope()
    val pagerState = rememberPagerState { pages.size }

    var menuOpen by remember { mutableStateOf(false) }
    var sheetOpen by rememberSaveable { mutableStateOf(false) }
    var askPlan by remember { mutableStateOf(false) }
    var addPageOpen by remember { mutableStateOf(false) }
    var pageToDelete by remember { mutableStateOf<Page?>(null) }
    var pageToDuplicate by remember { mutableStateOf<Page?>(null) }
    var pageToExport by remember { mutableStateOf<String?>(null) }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(PageExchange.MIME)) { uri ->
        val pageId = pageToExport
        pageToExport = null
        if (uri != null && pageId != null) viewModel.exportPage(pageId, uri)
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.importPage(uri)
    }

    LaunchedEffect(pageToShow, pages) {
        val index = pages.indexOfFirst { it.id == pageToShow }
        if (index >= 0) {
            pagerState.scrollToPage(index)
            viewModel.pageShown()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_name)) },
                actions = {
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Rounded.MoreVert, contentDescription = stringResource(R.string.action_menu))
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.packs_title)) },
                            onClick = {
                                menuOpen = false
                                viewModel.open(Screen.Packs)
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.settings_title)) },
                            onClick = {
                                menuOpen = false
                                viewModel.open(Screen.Settings)
                            },
                        )
                    }
                },
            )
        },
        bottomBar = {
            BottomBar(
                mix = mix,
                onTogglePlayback = viewModel::togglePlayback,
                onMode = { mode -> if (!viewModel.setMode(mode)) askPlan = true },
                onOpenNowPlaying = { sheetOpen = true },
                onApply = viewModel::applyPlan,
                onCancel = viewModel::cancelPlan,
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (pages.isNotEmpty()) {
                PrimaryScrollableTabRow(
                    selectedTabIndex = pagerState.currentPage.coerceIn(0, pages.lastIndex),
                    edgePadding = 8.dp,
                ) {
                    pages.forEachIndexed { index, page ->
                        PageTab(
                            page = page,
                            selected = index == pagerState.currentPage,
                            hasActiveCard = page.cardIds.any(mix::isActive),
                            canMoveLeft = index > 0,
                            canMoveRight = index < pages.lastIndex,
                            onClick = { scope.launch { pagerState.animateScrollToPage(index) } },
                            onEdit = { viewModel.editPage(page.id) },
                            onDuplicate = { pageToDuplicate = page },
                            onExport = {
                                pageToExport = page.id
                                exportLauncher.launch("${page.name.fr}.sonorama-page.json")
                            },
                            onDelete = { pageToDelete = page },
                            onMove = { delta -> viewModel.movePage(page.id, delta) },
                        )
                    }
                    Box(
                        Modifier
                            .heightIn(min = 56.dp)
                            .combinedClickable(role = Role.Button, onClick = { addPageOpen = true })
                            .padding(horizontal = 20.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Rounded.Add, contentDescription = stringResource(R.string.action_add_page))
                    }
                }
                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier.fillMaxSize(),
                    key = { pages.getOrNull(it)?.id ?: it },
                ) { index ->
                    val page = pages.getOrNull(index) ?: return@HorizontalPager
                    PageGrid(page, library, mix, viewModel)
                }
            }
        }
    }

    if (sheetOpen) {
        NowPlayingSheet(
            library = library,
            mix = mix,
            onDismiss = { sheetOpen = false },
            onVolume = viewModel::setVolume,
            onStop = viewModel::stopCard,
            onStopAll = viewModel::stopAll,
            onSaveMix = {
                sheetOpen = false
                viewModel.newSceneFromMix()
            },
        )
    }

    if (askPlan) {
        AlertDialog(
            onDismissRequest = { askPlan = false },
            title = { Text(stringResource(R.string.plan_leave_title)) },
            text = { Text(pluralStringResource(R.plurals.plan_leave_text, mix.plan.size, mix.plan.size)) },
            confirmButton = {
                Button(onClick = {
                    askPlan = false
                    viewModel.applyPlan()
                    viewModel.setMode(MixMode.DIRECT)
                }) { Text(stringResource(R.string.action_apply)) }
            },
            dismissButton = {
                TextButton(onClick = {
                    askPlan = false
                    viewModel.cancelPlan()
                    viewModel.setMode(MixMode.DIRECT)
                }) { Text(stringResource(R.string.action_discard)) }
            },
        )
    }

    if (addPageOpen) {
        AlertDialog(
            onDismissRequest = { addPageOpen = false },
            title = { Text(stringResource(R.string.action_add_page)) },
            text = { Text(stringResource(R.string.add_page_text)) },
            confirmButton = {
                Button(onClick = {
                    addPageOpen = false
                    viewModel.newPage()
                }) { Text(stringResource(R.string.action_new_page)) }
            },
            dismissButton = {
                TextButton(onClick = {
                    addPageOpen = false
                    importLauncher.launch(arrayOf(PageExchange.MIME, "text/plain", "application/octet-stream"))
                }) { Text(stringResource(R.string.action_import_page)) }
            },
        )
    }

    pageToDelete?.let { page ->
        AlertDialog(
            onDismissRequest = { pageToDelete = null },
            title = { Text(stringResource(R.string.delete_page_title, page.name.text())) },
            text = { Text(stringResource(R.string.delete_page_text)) },
            confirmButton = {
                Button(onClick = {
                    pageToDelete = null
                    viewModel.deletePage(page.id)
                }) { Text(stringResource(R.string.action_delete)) }
            },
            dismissButton = { TextButton(onClick = { pageToDelete = null }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }

    pageToDuplicate?.let { page ->
        val suggestion = stringResource(R.string.copy_of, page.name.text()).take(Limits.PAGE_NAME_MAX)
        var name by remember(page.id) { mutableStateOf(suggestion) }
        AlertDialog(
            onDismissRequest = { pageToDuplicate = null },
            title = { Text(stringResource(R.string.action_duplicate)) },
            text = {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(Limits.PAGE_NAME_MAX) },
                    label = { Text(stringResource(R.string.field_name)) },
                    singleLine = true,
                )
            },
            confirmButton = {
                Button(
                    enabled = name.isNotBlank(),
                    onClick = {
                        pageToDuplicate = null
                        viewModel.duplicatePage(page.id, name)
                    },
                ) { Text(stringResource(R.string.action_duplicate)) }
            },
            dismissButton = { TextButton(onClick = { pageToDuplicate = null }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PageTab(
    page: Page,
    selected: Boolean,
    hasActiveCard: Boolean,
    canMoveLeft: Boolean,
    canMoveRight: Boolean,
    onClick: () -> Unit,
    onEdit: () -> Unit,
    onDuplicate: () -> Unit,
    onExport: () -> Unit,
    onDelete: () -> Unit,
    onMove: (Int) -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val colors = MaterialTheme.colorScheme
    val tint = if (selected) colors.primary else colors.onSurfaceVariant
    val name = page.name.text()
    val playing = stringResource(R.string.tab_has_active)
    val selectedText = stringResource(R.string.tab_selected)
    Box(
        Modifier
            .heightIn(min = 56.dp)
            .combinedClickable(
                role = Role.Tab,
                onClick = onClick,
                onLongClick = { menuOpen = true },
                onLongClickLabel = stringResource(R.string.action_page_options),
            )
            .semantics(mergeDescendants = true) {
                contentDescription = if (hasActiveCard) "$name, $playing" else name
                if (selected) stateDescription = selectedText
            }
            .padding(horizontal = 14.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Symbols.icon(page.symbol), contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text(
                name,
                color = tint,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            // Point : au moins une carte de cette page est active.
            Spacer(Modifier.width(6.dp))
            Box(
                Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(if (hasActiveCard) colors.tertiary else androidx.compose.ui.graphics.Color.Transparent),
            )
        }
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            @Composable
            fun item(label: Int, action: () -> Unit) = DropdownMenuItem(
                text = { Text(stringResource(label)) },
                onClick = {
                    menuOpen = false
                    action()
                },
            )
            if (!page.builtIn) item(R.string.action_edit, onEdit)
            item(R.string.action_duplicate, onDuplicate)
            if (!page.builtIn) item(R.string.action_export, onExport)
            if (canMoveLeft) item(R.string.action_move_left) { onMove(-1) }
            if (canMoveRight) item(R.string.action_move_right) { onMove(1) }
            if (!page.builtIn) {
                HorizontalDivider()
                item(R.string.action_delete, onDelete)
            }
        }
    }
}

@Composable
private fun PageGrid(page: Page, library: Library, mix: MixState, viewModel: MainViewModel) {
    val cards = page.cardIds.mapNotNull { library.cards[it] }
    if (cards.isEmpty()) {
        Column(
            Modifier.fillMaxSize().padding(32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                stringResource(R.string.page_empty),
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (!page.builtIn) {
                Spacer(Modifier.height(16.dp))
                OutlinedButton(onClick = { viewModel.editPage(page.id) }) { Text(stringResource(R.string.action_edit)) }
            }
        }
        return
    }
    LazyVerticalGrid(
        // Trois colonnes sur un téléphone en portrait, davantage en paysage et sur tablette.
        columns = GridCells.Adaptive(minSize = 104.dp),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(cards, key = { it.id }) { card ->
            val change = mix.plan[card.id]
            val active = mix.isActive(card.id)
            val status = when {
                change?.toggle == true && active -> CardStatus.PENDING_STOP
                change?.toggle == true -> CardStatus.PENDING_START
                active -> CardStatus.ACTIVE
                else -> CardStatus.INACTIVE
            }
            CardTile(
                name = card.name.text(),
                icon = Symbols.icon(card.symbol),
                status = status,
                volume = change?.volume ?: mix.volumes[card.id] ?: card.volume,
                volumePlanned = change?.volume != null,
                available = card.available,
                onTap = { viewModel.tapCard(card) },
                onVolumeChange = { viewModel.setVolume(card.id, it, false) },
                onVolumeCommit = { viewModel.setVolume(card.id, it, true) },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

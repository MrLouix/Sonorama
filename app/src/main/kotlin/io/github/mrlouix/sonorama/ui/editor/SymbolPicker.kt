package io.github.mrlouix.sonorama.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.mrlouix.sonorama.R
import io.github.mrlouix.sonorama.ui.Symbols

/** Bouton montrant le symbole courant ; ouvre la liste classée par thème. */
@Composable
fun SymbolButton(symbol: String, onSymbol: (String) -> Unit, modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }
    OutlinedButton(onClick = { open = true }, modifier = modifier.heightIn(min = 56.dp)) {
        Icon(Symbols.icon(symbol), contentDescription = null)
        Text(stringResource(R.string.field_symbol), modifier = Modifier.padding(start = 8.dp))
    }
    if (open) {
        AlertDialog(
            onDismissRequest = { open = false },
            title = { Text(stringResource(R.string.symbol_picker_title)) },
            text = {
                LazyVerticalGrid(columns = GridCells.Adaptive(52.dp), modifier = Modifier.heightIn(max = 420.dp)) {
                    for (group in Symbols.groups) {
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            Text(
                                stringResource(group.title),
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
                            )
                        }
                        items(group.symbols, key = { "${group.title}/${it.first}" }) { (id, icon) ->
                            val isSelected = id == symbol
                            Box(
                                Modifier
                                    .padding(2.dp)
                                    .size(48.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(
                                        if (isSelected) MaterialTheme.colorScheme.primaryContainer
                                        else MaterialTheme.colorScheme.surfaceContainerHigh,
                                    )
                                    .clickable(role = Role.RadioButton) {
                                        onSymbol(id)
                                        open = false
                                    }
                                    .semantics { selected = isSelected },
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(icon, contentDescription = id.replace('_', ' '))
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { open = false }) { Text(stringResource(R.string.action_close)) } },
        )
    }
}

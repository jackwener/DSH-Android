package io.github.jackwener.dsh.feature.settings

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.jackwener.dsh.R
import io.github.jackwener.dsh.core.data.Effort
import io.github.jackwener.dsh.core.designsystem.BtnSize
import io.github.jackwener.dsh.core.designsystem.BtnVariant
import io.github.jackwener.dsh.core.designsystem.DButton
import io.github.jackwener.dsh.core.designsystem.DCheckbox
import io.github.jackwener.dsh.core.designsystem.DIcon
import io.github.jackwener.dsh.core.designsystem.DInput
import io.github.jackwener.dsh.core.designsystem.DSwitch
import io.github.jackwener.dsh.core.designsystem.Dsh
import io.github.jackwener.dsh.core.designsystem.DshType
import io.github.jackwener.dsh.core.designsystem.Radius
import io.github.jackwener.dsh.core.designsystem.Segmented
import io.github.jackwener.dsh.core.designsystem.SettingsCard
import io.github.jackwener.dsh.core.designsystem.SettingsGroupTitle
import io.github.jackwener.dsh.core.designsystem.SettingsRow
import io.github.jackwener.dsh.core.designsystem.Spinner
import io.github.jackwener.dsh.core.provider.CUSTOM
import io.github.jackwener.dsh.core.provider.DEEPSEEK_MODELS
import io.github.jackwener.dsh.core.provider.NetDiagnose
import io.github.jackwener.dsh.core.provider.NetDiagnosis
import io.github.jackwener.dsh.core.provider.PRESETS
import io.github.jackwener.dsh.core.provider.ProviderConfig
import io.github.jackwener.dsh.core.provider.ProviderPreset
import io.github.jackwener.dsh.core.provider.newRouteId
import io.github.jackwener.dsh.core.provider.presetOf
import kotlinx.coroutines.launch

/** Sub-page target of the 模型服务 page: [NEW_PROVIDER] or a provider's route id. */
const val NEW_PROVIDER = "\u0000new"

/** fetchState marker: the connection test failed with a network cause (shown from fetchNet). */
private const val NET_FAILURE = "\u0000net"

@Composable
private fun presetName(p: ProviderPreset) = if (p.id == CUSTOM) stringResource(R.string.prov_custom) else p.name

@Composable
private fun chevron() = DIcon(R.drawable.dsw_chevron_right_outline, size = 14.dp, tint = Dsh.colors.labelTertiary)

/** 第三方服务商: one row per provider (switch = shown in the picker), then 添加服务商. */
@Composable
fun ProvidersCard(vm: SettingsViewModel, onOpen: (String) -> Unit) {
    val c = Dsh.colors
    val s by vm.settings.collectAsStateWithLifecycle()
    SettingsGroupTitle(stringResource(R.string.prov_group))
    Text(stringResource(R.string.prov_group_desc), Modifier.padding(start = 4.dp, bottom = 8.dp), style = DshType.xs13, color = c.labelTertiary)
    SettingsCard {
        s.providers.forEachIndexed { i, p ->
            SettingsRow(p.name, stringResource(R.string.prov_models_count, p.models.size) + " · " + (Uri.parse(p.baseUrl).host ?: p.baseUrl),
                onClick = { onOpen(p.id) }, divider = i > 0,
                trailing = { DSwitch(p.enabled, { vm.setProviderEnabled(p, it) }) })
        }
        SettingsRow(stringResource(R.string.prov_add), onClick = { onOpen(NEW_PROVIDER) }, divider = s.providers.isNotEmpty(), titleColor = c.link,
            leading = { DIcon(R.drawable.dsw_plus_outline, size = 16.dp, tint = c.link) })
    }
}

/** 默认模型: DeepSeek models, then each enabled provider's models; effort only for DeepSeek. */
@Composable
fun DefaultModelCard(vm: SettingsViewModel) {
    val c = Dsh.colors
    val s by vm.settings.collectAsStateWithLifecycle()
    val choice = s.choice
    SettingsGroupTitle(stringResource(R.string.set_default_model))
    SettingsCard {
        DEEPSEEK_MODELS.forEachIndexed { i, (id, label) ->
            SettingsRow(label, stringResource(if (id == "deepseek-flash") R.string.model_flash_desc else R.string.model_pro_desc),
                onClick = { vm.setModel("", id) }, divider = i > 0,
                trailing = { if (choice.isDeepSeek && choice.model == id) DIcon(R.drawable.dsw_check_outline, size = 16.dp, tint = c.labelPrimary) })
        }
        s.providers.filter { it.enabled }.forEach { p ->
            p.models.forEach { m ->
                SettingsRow(m, p.name, onClick = { vm.setModel(p.id, m) },
                    trailing = { if (choice.provider?.id == p.id && choice.model == m) DIcon(R.drawable.dsw_check_outline, size = 16.dp, tint = c.labelPrimary) })
            }
        }
        if (choice.isDeepSeek) SettingsRow(stringResource(R.string.effort), stringResource(R.string.effort_desc), stacked = true) {
            Segmented(Effort.entries.map { it.label }, s.effort.ordinal, { vm.setEffort(Effort.entries[it]) })
        } else SettingsRow(stringResource(R.string.effort), stringResource(R.string.prov_model_desc))
    }
}

/** Add (preset list → editor) or edit one provider. */
@Composable
fun ProviderPage(vm: SettingsViewModel, target: String, onDone: () -> Unit) {
    val s by vm.settings.collectAsStateWithLifecycle()
    val existing = s.providers.firstOrNull { it.id == target }
    var presetId by rememberSaveable(target) { mutableStateOf(existing?.preset) }
    val preset = presetOf(presetId)
    if (existing == null && preset == null) {
        // Step 1: pick a preset.
        Spacer(Modifier.height(8.dp))
        SettingsGroupTitle(stringResource(R.string.prov_choose))
        SettingsCard {
            PRESETS.forEachIndexed { i, p ->
                SettingsRow(presetName(p), p.baseUrl.ifEmpty { null }, onClick = { presetId = p.id }, divider = i > 0, trailing = { chevron() })
            }
        }
        return
    }
    val p = preset ?: presetOf(CUSTOM)!!
    val customName = stringResource(R.string.prov_custom)
    androidx.compose.runtime.key(target, p.id) { ProviderEditor(vm, existing, p, if (p.id == CUSTOM) customName else p.name, onDone) }
}

@Composable
private fun ProviderEditor(vm: SettingsViewModel, existing: ProviderConfig?, preset: ProviderPreset, defaultName: String, onDone: () -> Unit) {
    val c = Dsh.colors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val s by vm.settings.collectAsStateWithLifecycle()
    var name by rememberSaveable { mutableStateOf(existing?.name ?: defaultName) }
    var baseUrl by rememberSaveable { mutableStateOf(existing?.baseUrl ?: preset.baseUrl) }
    var key by rememberSaveable { mutableStateOf("") }
    val selected = remember { mutableStateListOf<String>().apply { addAll(existing?.models ?: preset.models) } }
    val offered = remember { mutableStateListOf<String>().apply { addAll(existing?.models ?: preset.models) } }
    /** Model ids the user typed in by hand: a refresh keeps these, the endpoint's answer replaces the rest. */
    val manual = remember { mutableStateListOf<String>() }
    var typed by rememberSaveable { mutableStateOf("") }
    var fetchState by remember { mutableStateOf<String?>(null) } // null | "busy" | message
    var fetchOk by remember { mutableStateOf(false) }
    var fetchNet by remember { mutableStateOf<NetDiagnosis?>(null) }
    var error by remember { mutableStateOf<Int?>(null) }
    val needsKey = !preset.keyOptional && existing?.hasKey != true

    Spacer(Modifier.height(8.dp))
    SettingsCard {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(stringResource(R.string.prov_name), style = DshType.xs13m, color = c.labelSecondary)
            DInput(name, { name = it }, placeholder = defaultName)
            Text(stringResource(R.string.prov_base_url), style = DshType.xs13m, color = c.labelSecondary)
            DInput(baseUrl, { baseUrl = it.trim() }, placeholder = "https://…/v1", keyboardType = KeyboardType.Uri)
            Text(stringResource(R.string.prov_base_url_hint), style = DshType.xxs12, color = c.labelTertiary)
            if (baseUrl.startsWith("http://", ignoreCase = true)) Text(stringResource(R.string.prov_http_warning), style = DshType.xxs12, color = c.error)
            // Warn, but allow saving: something may really listen on the phone (e.g. Termux).
            if (NetDiagnose.isOnDeviceAddress(baseUrl)) Text(stringResource(R.string.prov_local_warning), style = DshType.xxs12, color = c.amber)
            Text(stringResource(if (preset.keyOptional) R.string.prov_key_optional else R.string.api_key_label), style = DshType.xs13m, color = c.labelSecondary)
            DInput(key, { key = it }, placeholder = if (existing?.hasKey == true) stringResource(R.string.prov_key_saved) else "sk-…", secret = true, keyboardType = KeyboardType.Password)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val keysUrl = preset.keysUrl
                if (keysUrl != null) Text(stringResource(R.string.prov_get_key), Modifier.weight(1f).clip(Radius.sm).clickable {
                    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(keysUrl)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                }.padding(vertical = 4.dp), style = DshType.xs13, color = c.link) else Spacer(Modifier.weight(1f))
                DButton(stringResource(R.string.prov_fetch), {
                    if (!isHttpUrl(baseUrl)) { error = R.string.prov_err_url; return@DButton }
                    fetchState = "busy"
                    scope.launch {
                        vm.fetchModels(baseUrl, key, existing).onSuccess { ids ->
                            fetchOk = true
                            fetchState = context.getString(R.string.prov_fetch_ok, ids.size)
                            // A refresh *replaces* the form, it does not merge into it: models an earlier
                            // call listed but the endpoint no longer returns have to go, or the count above
                            // says "共 N 个模型" while the list still reads exactly as before (they were only
                            // ever appended, below the fold). What the user chose to keep survives: models
                            // they typed in, and ones already ticked (dropping those would silently edit a
                            // saved provider).
                            val kept = offered.filter { it in manual || it in selected }
                            offered.clear(); offered.addAll(ids)
                            kept.forEach { if (it !in offered) offered.add(it) }
                        }.onFailure { t ->
                            fetchOk = false
                            // Same wording as a failed chat turn (Stream.kt ErrorRow): cause, hint, raw detail.
                            fetchNet = NetDiagnose.ofException(t)
                            fetchState = if (fetchNet != null) NET_FAILURE else context.getString(R.string.prov_fetch_failed, t.message ?: t.javaClass.simpleName)
                        }
                    }
                }, variant = BtnVariant.Outline, size = BtnSize.Sm, enabled = fetchState != "busy" && baseUrl.isNotBlank())
            }
            when (val f = fetchState) {
                null -> {}
                "busy" -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Spinner(12.dp); Text(stringResource(R.string.prov_fetching), style = DshType.xxs12, color = c.labelTertiary)
                }
                NET_FAILURE -> fetchNet?.let { net ->
                    Text(stringResource(R.string.prov_fetch_failed, stringResource(net.cause.message)) + (net.cause.hint?.let { " " + stringResource(it) } ?: ""),
                        style = DshType.xxs12, color = c.error)
                    Text(net.detail.take(300), style = DshType.mono11, color = c.labelTertiary)
                }
                else -> Text(f, style = DshType.xxs12, color = if (fetchOk) c.labelTertiary else c.error)
            }
        }
    }

    SettingsGroupTitle(stringResource(R.string.model))
    Text(stringResource(R.string.prov_models_desc), Modifier.padding(start = 4.dp, bottom = 8.dp), style = DshType.xs13, color = c.labelTertiary)
    SettingsCard {
        offered.forEachIndexed { i, m ->
            val on = m in selected
            SettingsRow(m, onClick = { if (on) selected.remove(m) else selected.add(m) }, divider = i > 0,
                trailing = { DCheckbox(on, { if (it) selected.add(m) else selected.remove(m) }) })
        }
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DInput(typed, { typed = it.trim() }, Modifier.weight(1f), placeholder = stringResource(R.string.prov_add_model))
            DButton(stringResource(R.string.prov_add_model_btn), {
                if (typed.isNotEmpty()) {
                    if (typed !in offered) offered.add(typed)
                    if (typed !in manual) manual.add(typed)
                    if (typed !in selected) selected.add(typed)
                    typed = ""
                }
            }, variant = BtnVariant.Outline, size = BtnSize.Sm, enabled = typed.isNotEmpty())
        }
    }

    error?.let { Text(stringResource(it), Modifier.padding(start = 4.dp, top = 12.dp), style = DshType.xs13, color = c.error) }
    Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End), verticalAlignment = Alignment.CenterVertically) {
        if (existing != null) DButton(stringResource(R.string.prov_delete), { vm.deleteProvider(existing); onDone() }, variant = BtnVariant.Danger, size = BtnSize.Sm)
        Spacer(Modifier.weight(1f))
        DButton(stringResource(R.string.cancel), onDone, variant = BtnVariant.Outline, size = BtnSize.Sm)
        DButton(stringResource(R.string.save), {
            error = when {
                !isHttpUrl(baseUrl) -> R.string.prov_err_url
                needsKey && key.isBlank() -> R.string.prov_err_key
                selected.isEmpty() -> R.string.prov_err_models
                else -> null
            }
            if (error == null) scope.launch {
                val id = existing?.id ?: newRouteId(preset.id, s.providers.map { it.id })
                // Keep the user's order of discovery, checked ones only.
                val models = offered.filter { it in selected }
                vm.saveProvider(ProviderConfig(id, preset.id, name.trim().ifEmpty { defaultName }, baseUrl.trimEnd('/'), models,
                    enabled = existing?.enabled ?: true, hasKey = existing?.hasKey ?: false), key)
                onDone()
            }
        }, size = BtnSize.Sm)
    }
}

/** https:// for cloud services, or plain http:// for a gateway on the user's own LAN (#5). */
private fun isHttpUrl(url: String) = url.startsWith("https://", ignoreCase = true) || url.startsWith("http://", ignoreCase = true)

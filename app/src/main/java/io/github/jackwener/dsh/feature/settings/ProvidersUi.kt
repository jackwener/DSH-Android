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
import androidx.compose.runtime.mutableStateMapOf
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
import io.github.jackwener.dsh.core.designsystem.Fmt
import io.github.jackwener.dsh.core.designsystem.IconBtn
import io.github.jackwener.dsh.core.designsystem.Radius
import io.github.jackwener.dsh.core.designsystem.Segmented
import io.github.jackwener.dsh.core.designsystem.SettingsCard
import io.github.jackwener.dsh.core.designsystem.SettingsGroupTitle
import io.github.jackwener.dsh.core.designsystem.SettingsRow
import io.github.jackwener.dsh.core.designsystem.Spinner
import io.github.jackwener.dsh.core.provider.CUSTOM
import io.github.jackwener.dsh.core.provider.DEEPSEEK_MODELS
import io.github.jackwener.dsh.core.provider.EffectiveLimits
import io.github.jackwener.dsh.core.provider.LimitSource
import io.github.jackwener.dsh.core.provider.ModelLimits
import io.github.jackwener.dsh.core.provider.NetDiagnose
import io.github.jackwener.dsh.core.provider.NetDiagnosis
import io.github.jackwener.dsh.core.provider.PRESETS
import io.github.jackwener.dsh.core.provider.ProviderConfig
import io.github.jackwener.dsh.core.provider.ProviderPreset
import io.github.jackwener.dsh.core.provider.RemoteModel
import io.github.jackwener.dsh.core.provider.ThirdParty
import io.github.jackwener.dsh.core.provider.parseTokenInput
import io.github.jackwener.dsh.core.provider.refreshOffered
import io.github.jackwener.dsh.core.provider.newRouteId
import io.github.jackwener.dsh.core.provider.presetOf
import kotlinx.coroutines.launch

/** Sub-page target of the 模型服务 page: [NEW_PROVIDER] or a provider's route id. */
const val NEW_PROVIDER = "\u0000new"

/** fetchState marker: the connection test failed with a network cause (shown from fetchNet). */
private const val NET_FAILURE = "\u0000net"

@Composable
private fun presetName(p: ProviderPreset) = if (p.id == CUSTOM) stringResource(R.string.prov_custom) else p.name

/** Label for where a limit came from (also used by the composer's context ring). */
fun limitSourceLabel(s: LimitSource) = when (s) {
    LimitSource.DETECTED -> R.string.limit_src_detected
    LimitSource.MANUAL -> R.string.limit_src_manual
    LimitSource.DEFAULT -> R.string.limit_src_default
}

/** Blank or invalid → null (invalid text is reported on save). */
private fun tokensOrNull(text: String): Long? = parseTokenInput(text).getOrNull()
private fun Long.toIntSat(): Int = coerceAtMost(Int.MAX_VALUE.toLong()).toInt()

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
    var typed by rememberSaveable { mutableStateOf("") }
    // Names typed in by hand this visit: kept across a refresh even when unchecked.
    val manual = remember { mutableStateListOf<String>() }
    // Kept models the last successful fetch did not return (shown on top, flagged).
    val missing = remember { mutableStateListOf<String>() }
    // Limits: provider-level text, per-model override text (window, max output), and /models values.
    var ctxText by rememberSaveable { mutableStateOf(existing?.contextWindow?.toString().orEmpty()) }
    var maxText by rememberSaveable { mutableStateOf(existing?.maxTokens?.toString().orEmpty()) }
    val overrides = remember {
        mutableStateMapOf<String, Pair<String, String>>().apply {
            existing?.limits?.forEach { (m, l) -> if (l.contextWindow != null || l.maxTokens != null) put(m, (l.contextWindow?.toString().orEmpty()) to (l.maxTokens?.toString().orEmpty())) }
        }
    }
    val detected = remember {
        mutableStateMapOf<String, RemoteModel>().apply {
            existing?.limits?.forEach { (m, l) -> put(m, RemoteModel(m, l.detectedContextWindow, l.detectedMaxTokens)) }
        }
    }
    var editing by remember { mutableStateOf<String?>(null) }
    fun stored(m: String, withOverride: Boolean = true): ModelLimits {
        val o = overrides[m].takeIf { withOverride }
        val d = detected[m]
        return ModelLimits(o?.first?.let(::tokensOrNull), o?.second?.let(::tokensOrNull)?.toIntSat(), d?.contextWindow, d?.maxTokens)
    }
    fun live(m: String, withOverride: Boolean = true): EffectiveLimits =
        ThirdParty.resolve(stored(m, withOverride), tokensOrNull(ctxText), tokensOrNull(maxText)?.toIntSat())
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
                        vm.fetchModels(baseUrl, key, existing).onSuccess { list ->
                            fetchOk = true
                            fetchState = context.getString(R.string.prov_fetch_ok, list.size)
                            // A fresh listing replaces what it reports on (fields it dropped are dropped here too).
                            list.forEach { detected[it.id] = it }
                            val r = refreshOffered(offered.toList(), list.map { it.id }, selected.toList() + manual)
                            offered.clear(); offered.addAll(r.offered)
                            missing.clear(); missing.addAll(r.missing)
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
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(stringResource(R.string.prov_context_window), style = DshType.xs13m, color = c.labelSecondary)
                    DInput(ctxText, { ctxText = it }, placeholder = stringResource(R.string.prov_limit_auto), keyboardType = KeyboardType.Number)
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(stringResource(R.string.prov_max_output), style = DshType.xs13m, color = c.labelSecondary)
                    DInput(maxText, { maxText = it }, placeholder = stringResource(R.string.prov_limit_auto), keyboardType = KeyboardType.Number)
                }
            }
            Text(stringResource(R.string.prov_limits_hint, ThirdParty.DEFAULT_CONTEXT_WINDOW, ThirdParty.DEFAULT_MAX_TOKENS), style = DshType.xxs12, color = c.labelTertiary)
        }
    }

    SettingsGroupTitle(stringResource(R.string.model))
    Text(stringResource(R.string.prov_models_desc), Modifier.padding(start = 4.dp, bottom = 8.dp), style = DshType.xs13, color = c.labelTertiary)
    SettingsCard {
        offered.forEachIndexed { i, m ->
            val on = m in selected
            val l = live(m)
            val line = stringResource(R.string.prov_limits_line, Fmt.tokens(l.contextWindow), stringResource(limitSourceLabel(l.contextSource)),
                Fmt.tokens(l.maxTokens.toLong()), stringResource(limitSourceLabel(l.maxTokensSource)))
            SettingsRow(m, if (m in missing) stringResource(R.string.prov_model_missing) + " · " + line else line,
                onClick = { if (on) selected.remove(m) else selected.add(m) }, divider = i > 0,
                trailing = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconBtn(R.drawable.dsw_edit_outline, { editing = if (editing == m) null else m }, size = 32.dp, iconSize = 14.dp,
                            tint = if (m in overrides) c.link else c.labelTertiary, contentDescription = stringResource(R.string.prov_model_limits))
                        DCheckbox(on, { if (it) selected.add(m) else selected.remove(m) })
                    }
                })
            if (editing == m) {
                val base = live(m, withOverride = false)
                val o = overrides[m] ?: ("" to "")
                fun set(v: Pair<String, String>) { if (v.first.isBlank() && v.second.isBlank()) overrides.remove(m) else overrides[m] = v }
                Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(stringResource(R.string.prov_context_window), style = DshType.xxs12, color = c.labelSecondary)
                            DInput(o.first, { set(it to o.second) }, placeholder = base.contextWindow.toString(), keyboardType = KeyboardType.Number)
                        }
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(stringResource(R.string.prov_max_output), style = DshType.xxs12, color = c.labelSecondary)
                            DInput(o.second, { set(o.first to it) }, placeholder = base.maxTokens.toString(), keyboardType = KeyboardType.Number)
                        }
                    }
                    Text(stringResource(R.string.prov_model_limits_hint), style = DshType.xxs12, color = c.labelTertiary)
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DInput(typed, { typed = it.trim() }, Modifier.weight(1f), placeholder = stringResource(R.string.prov_add_model))
            DButton(stringResource(R.string.prov_add_model_btn), {
                if (typed.isNotEmpty()) { if (typed !in offered) offered.add(typed); if (typed !in manual) manual.add(typed); if (typed !in selected) selected.add(typed); typed = "" }
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
                (listOf(ctxText, maxText) + overrides.values.flatMap { listOf(it.first, it.second) }).any { parseTokenInput(it).isFailure } -> R.string.prov_err_limits
                else -> null
            }
            if (error == null) scope.launch {
                val id = existing?.id ?: newRouteId(preset.id, s.providers.map { it.id })
                // Keep the user's order of discovery, checked ones only.
                val models = offered.filter { it in selected }
                val limits = models.associateWith { stored(it) }.filterValues { !it.isEmpty }
                vm.saveProvider(ProviderConfig(id, preset.id, name.trim().ifEmpty { defaultName }, baseUrl.trimEnd('/'), models,
                    enabled = existing?.enabled ?: true, hasKey = existing?.hasKey ?: false,
                    contextWindow = tokensOrNull(ctxText), maxTokens = tokensOrNull(maxText)?.toIntSat(), limits = limits), key)
                onDone()
            }
        }, size = BtnSize.Sm)
    }
}

/** https:// for cloud services, or plain http:// for a gateway on the user's own LAN (#5). */
private fun isHttpUrl(url: String) = url.startsWith("https://", ignoreCase = true) || url.startsWith("http://", ignoreCase = true)

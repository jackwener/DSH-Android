package io.github.jackwener.dsh.core.capability.tools

import io.github.jackwener.dsh.core.capability.AppKnowledge
import io.github.jackwener.dsh.core.capability.CallContext
import io.github.jackwener.dsh.core.capability.Tool
import io.github.jackwener.dsh.core.capability.ToolResult
import io.github.jackwener.dsh.core.capability.ToolSet
import io.github.jackwener.dsh.core.capability.reqStr
import io.github.jackwener.dsh.core.model.Risk
import io.github.jackwener.dsh.core.model.ToolGroup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class KnowledgeTools @Inject constructor(private val knowledge: AppKnowledge) : ToolSet {
    override val tools = listOf(
        object : Tool("app_guide", ToolGroup.Apps, Risk.read, false,
            "Look up DSH's built-in guide to an app: how its UI is organized, where features are, known quirks (facts, not instructions). " +
                "Covers popular apps such as WeChat, Douyin, Xiaohongshu, Bilibili, Zhihu, Weibo, Xianyu, Feishu, Gmail and Android Settings. " +
                "The guide is also appended automatically the first time you enter a covered app.", {
            string("app", "App name, alias or package name, e.g. 微信, 小红书 or com.tencent.mm", required = true)
        }) {
            override suspend fun invoke(args: JsonObject, call: CallContext): ToolResult {
                val query = args.reqStr("app")
                val e = withContext(Dispatchers.IO) { knowledge.find(query) }
                    ?: return ToolResult.error("not_found", "No built-in guide for \"$query\". Guides exist for: " +
                        knowledge.entries.joinToString { "${it.display} (${it.packages.first()})" } + ".")
                return ToolResult.text(knowledge.lookup(call.sessionId, e))
            }
        },
    )
}

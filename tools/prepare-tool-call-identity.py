#!/usr/bin/env python3
"""Generate #66 recipes from the canonical locked 0.2.2-rc.5 npm tree; no runtime execution."""
import argparse, hashlib, json
from pathlib import Path
from source_text import write_text
ROOT = Path(__file__).resolve().parents[1]
ASSETS = ROOT / 'app/src/main/assets'
HISTORY_HASHES = {
    '@deepseek-ai/dsh-client-ui-chat/lib/client.js': 'eef53e82b8cd584d2f0e543a4a93395f9d90df3bba1218891bd99d07d788de5c',
    '@deepseek-ai/dsh-client-ui-tool/lib/client.js': 'f3de614452e676d2afde6c8e5305f17e1f8784f672c3208a5366c8bf132d667b',
}

def main():
    ap = argparse.ArgumentParser(); ap.add_argument('modules', type=Path); args = ap.parse_args()
    modules = args.modules.resolve()
    expected = json.loads((ROOT/'tools/dsh-runtime/package.json').read_text())['dependencies']['@deepseek-ai/dsh']
    if expected != '0.2.2-rc.5': raise ValueError('TOOL_IDENTITY_DSH_VERSION')
    lock = json.loads((ROOT/'tools/dsh-runtime/package-lock.json').read_text(encoding='utf8'))['packages']
    def recipe(asset, module, patches, previous=()):
        file = modules/module; source = file.read_text(encoding='utf8')
        package = json.loads((modules/Path(module).parts[0]/Path(module).parts[1]/'package.json').read_text())
        probe = source
        for before, after in patches:
            if probe.count(before) != 1: raise ValueError('TOOL_IDENTITY_ANCHOR:'+asset)
            probe = probe.replace(before, after)
        digest = hashlib.sha256(file.read_bytes()).hexdigest()
        value = dict(dshVersion=expected, module=module, packageVersion=package['version'],
                     canonicalSourceSha256=digest)
        if module in HISTORY_HASHES:
            name = module.split('/lib/')[0]
            locked = lock['node_modules/' + name]
            if (digest != HISTORY_HASHES[module] or package['name'] != name
                    or package['version'] != locked['version'] or locked['version'] != expected):
                raise ValueError('TOOL_HISTORY_SOURCE:'+asset)
            value.update(sourceSha256=digest, packageIntegrity=locked['integrity'])
        value['patches'] = [dict(before=a, after=b) for a,b in patches]
        if previous:
            value['previousPatches'] = [dict(before=a, after=b) for a,b in previous]
        write_text(ASSETS/asset, json.dumps(value, ensure_ascii=False, indent=2)+'\n', encoding='utf8')
    valid = 'typeof block.id === "string" && block.id.trim() !== "" && typeof block.name === "string" && block.name.trim() !== ""'
    recipe('tool-stream-identity-patch.json', '@deepseek-ai/dsh-llm-pi-ai/lib/index.js', [
      ('\t\t\tconst known = toolIds.get(event.contentIndex);', '''\t\t\t// DSHA_TOOL_LATE_IDENTITY_RC2_V1: providers may populate identity after start.
\t\t\tconst previous = toolIds.get(event.contentIndex);
\t\t\tconst partial = event.partial?.content?.[event.contentIndex];
\t\t\tconst current = partial?.type === "toolCall" ? partial : undefined;
\t\t\tconst known = {
\t\t\t\tid: typeof current?.id === "string" && current.id.trim() ? current.id : previous?.id ?? "",
\t\t\t\tname: typeof current?.name === "string" && current.name.trim() ? current.name : previous?.name ?? ""
\t\t\t};
\t\t\ttoolIds.set(event.contentIndex, known);'''),
      ('function assertSupportedHistory(messages) {\n\tfor (const message of messages) {', '''function assertSupportedHistory(messages) {
\t// DSHA_TOOL_HISTORY_READ_ONLY_RC2_V1: reject malformed legacy protocol before HTTP; never rewrite it.
\tfor (const message of messages) {
\t\tif (message.role === "assistant" && message.content.some(block => block.type === "tool-call" && !('''+valid+''')))
\t\t\tthrow new LlmError("DSHA_INVALID_TOOL_HISTORY: incomplete tool identity; retained history is read only. Start a new session.", "INVALID_REQUEST");
\t\tif (message.role === "tool" && (typeof message.toolCallId !== "string" || !message.toolCallId.trim()))
\t\t\tthrow new LlmError("DSHA_INVALID_TOOL_HISTORY: missing result call id; retained history is read only.", "INVALID_REQUEST");''')])
    recipe('tool-block-identity-patch.json', '@deepseek-ai/dsh-llm/lib/types/assembler.js', [
      ('                partial.toolCallId = chunk.id;', "                // DSHA_TOOL_ASSEMBLER_IDENTITY_RC2_V1: empty deltas must not erase a late valid identity.\n                if (typeof chunk.id === 'string' && chunk.id.trim()) partial.toolCallId = chunk.id;"),
      ('                if (chunk.name)', "                if (typeof chunk.name === 'string' && chunk.name.trim())"),
      ('                partial.block = chunk.block;', '''                partial.block = chunk.block.type === 'tool-call' ? {
                    ...chunk.block,
                    id: typeof chunk.block.id === 'string' && chunk.block.id.trim() ? chunk.block.id : partial.toolCallId ?? '',
                    name: typeof chunk.block.name === 'string' && chunk.block.name.trim() ? chunk.block.name : partial.toolCallName ?? '',
                } : chunk.block;'''),
      ('id: partial.toolCallId ?? brandString(`call-${index}`),', "id: partial.toolCallId ?? '',")])
    # 0.2.2 moved executeToolBatch (`const planned = toolCalls.map`) from dsh-agent-loop to
    # dsh-tools/lib/pipeline.js, shared by the native and PTC executors; it imports HarnessError, not LlmError.
    recipe('tool-dispatch-validation-pipeline-patch.json', '@deepseek-ai/dsh-tools/lib/pipeline.js', [
      ('\tconst settlement = new BatchSettlement(input);\n\ttry {\n\t\tconst planned = toolCalls.map((block) => ({', '''\t// DSHA_TOOL_DISPATCH_IDENTITY_V2: preflight the whole batch before the first session event or side effect.
\tif (toolCalls.some(block => !('''+valid+'''))) throw new HarnessError("DSHA_INVALID_TOOL_CALL: missing name/id; this step was not dispatched. No automatic replay.", "INVALID_RESPONSE");
\tconst settlement = new BatchSettlement(input);
\ttry {
\t\tconst planned = toolCalls.map((block) => ({''')])
    # appendSkippedToolCall no longer exists in 0.2.2. The step's upstream ToolCallRecovery
    # closes every pending valid call of the recorded assistant message with a not-started
    # error result when this step throws, so no tool/call event or body runs here.
    recipe('tool-dispatch-validation-patch.json', '@deepseek-ai/dsh-agent-loop/lib/index.js', [
      ('\t\t\t\tconst message = createAssistantMessage({\n\t\t\t\t\tcontent: live.blocks(),', '''\t\t\t\t// DSHA_TOOL_FINISH_IDENTITY_RC2_V1: preserve valid structure, render malformed calls as non-executable diagnostics.
\t\t\t\tconst assembledBlocks = live.blocks();
\t\t\t\tconst invalidIdentity = assembledBlocks.some(block => block.type === "tool-call" && !('''+valid+'''));
\t\t\t\tconst message = createAssistantMessage({
\t\t\t\t\tcontent: assembledBlocks.map(block => block.type === "tool-call" && !('''+valid+''') ? { type: "text", text: "[DSHA_INVALID_TOOL_CALL] Missing tool name/id in this step. No tool in this step was executed; no automatic replay." } : block),'''),
      ('\t\t\t\t\t\t...live.replayState !== void 0 ? { replayState: live.replayState } : {}', '\t\t\t\t\t\t...!invalidIdentity && live.replayState !== void 0 ? { replayState: live.replayState } : {}'),
      ('\t\t\t\tif (finish.kind === "max-tokens") return { kind: "max-tokens" };', '''\t\t\t\t// DSHA_TOOL_FINISH_IDENTITY_V2: dispatch nothing; the step's ToolCallRecovery closes valid calls as not started.
\t\t\t\tif (invalidIdentity) throw new LlmError("DSHA_INVALID_TOOL_CALL: missing name/id; this step was not dispatched. No automatic replay.", "INVALID_RESPONSE");
\t\t\t\tif (finish.kind === "max-tokens") return { kind: "max-tokens" };''')])
    recipe('tool-messages-history-patch.json', '@deepseek-ai/dsh-llm-deepseek/lib/index.js', [
      ('\tconst model = connection.models.find((entry) => entry.id === options.model);\n\tconst inHistory =', '''\t// DSHA_MESSAGES_TOOL_HISTORY_READ_ONLY_RC2_V1: validation precedes request serialization/HTTP.
\tfor (const message of history) {
\t\tif (message.role === "assistant" && message.content.some(block => block.type === "tool-call" && !('''+valid+''')))
\t\t\tthrow new LlmError("DSHA_INVALID_TOOL_HISTORY: incomplete tool identity; retained history is read only. Start a new session.", "INVALID_REQUEST");
\t\tif (message.role === "tool" && (typeof message.toolCallId !== "string" || !message.toolCallId.trim()))
\t\t\tthrow new LlmError("DSHA_INVALID_TOOL_HISTORY: missing result call id; retained history is read only.", "INVALID_REQUEST");
\t}
\tconst model = connection.models.find((entry) => entry.id === options.model);
\tconst inHistory =''')])
    recipe('tool-history-projection-identity-patch.json', '@deepseek-ai/dsh-client-ui-chat/lib/client.js', [
      ('function rootCall(match,previous){', '''function dshaToolHistoryIdentityInvalid(id, name) {
    // Check the original fields before the view converts IDs to string keys.
    return typeof id !== "string" || !id.trim() || typeof name !== "string" || !name.trim();
}
function rootCall(match,previous){'''),
      ('callId:String(match.event.data.callId),name:match.event.data.name,',
       'callId:String(match.event.data.callId),dshaInvalidIdentity:dshaToolHistoryIdentityInvalid(match.event.data.callId,match.event.data.name),name:match.event.data.name,'),
      ('callId:String(chunk.id),name:chunk.name,',
       'callId:String(chunk.id),dshaInvalidIdentity:dshaToolHistoryIdentityInvalid(chunk.id,chunk.name),name:chunk.name,'),
      ('root:{...root,name:chunk.block.name,args:root.args.settle(chunk.block.arguments)}',
       'root:{...root,dshaInvalidIdentity:root.dshaInvalidIdentity===true||dshaToolHistoryIdentityInvalid(chunk.block.id,chunk.block.name),name:chunk.block.name,args:root.args.settle(chunk.block.arguments)}'),
      ('current.name===root.name&&current.args===root.args',
       'current.name===root.name&&current.args===root.args&&current.dshaInvalidIdentity===root.dshaInvalidIdentity'),
      ('callId:String(message.source.callId),name:previous?.name??``,',
       'callId:String(message.source.callId),'
       'dshaInvalidIdentity:previous?.dshaInvalidIdentity===true||'
       'dshaToolHistoryIdentityInvalid(message.source.callId,previous?.name),'
       'name:previous?.name??``,'),
      ('callId:data.subCallId,parentCallId:data.parentCallId,name:data.name,argsRaw:jsonArguments(data.arguments),',
       'callId:data.subCallId,parentCallId:data.parentCallId,dshaInvalidIdentity:dshaToolHistoryIdentityInvalid(data.subCallId,data.name)||dshaToolHistoryIdentityInvalid(data.parentCallId,data.name),name:data.name,argsRaw:jsonArguments(data.arguments),'),
      ('callId:data.subCallId,parentCallId:data.parentCallId,name:data.name,args:previous',
       'callId:data.subCallId,parentCallId:data.parentCallId,dshaInvalidIdentity:previous?.dshaInvalidIdentity===true||dshaToolHistoryIdentityInvalid(data.subCallId,data.name)||dshaToolHistoryIdentityInvalid(data.parentCallId,data.name),name:data.name,args:previous'),
      ('time:interruptedAt.time,callId:block.callId,',
       'time:interruptedAt.time,callId:block.callId,dshaInvalidIdentity:block.dshaInvalidIdentity===true,')])
    call_name = 'function callName(call){return call.phase===`result`?call.block.call?.name??``:call.block.name}'
    previous_display = '''function callName(call){
\t\t\t// DSHA_TOOL_HISTORY_DISPLAY_RC2_V1: no named tool view/action for malformed retained rows.
\t\t\tconst name = call.phase === "result" ? call.block.call?.name ?? "" : call.block.name;
\t\t\tconst id = call.block.callId;
\t\t\treturn typeof name === "string" && name.trim() && typeof id === "string" && id.trim() ? name : "DSHA_INVALID_TOOL_CALL (read only)";
}'''
    generic_card = 'function GenericToolCard({toolName,block,cwd,home,openFile,getFileTriggerProps,inspect,useDisclosure,t}){'
    call_row = 'const autoReviewDenied=(0,react.useMemo)(()=>call.phase===`result`&&toolRowModel(toolName,call.block).autoReviewDenial!==null,[toolName,call]);'
    recipe('tool-history-display-patch.json', '@deepseek-ai/dsh-client-ui-tool/lib/client.js', [
      (call_name, '''function dshaToolHistoryName(block) {
    return "kind" in block ? block.call?.name ?? "" : block.name;
}
function dshaToolHistoryInvalid(block) {
    const name = dshaToolHistoryName(block);
    return block.dshaInvalidIdentity === true || typeof block.callId !== "string" || !block.callId.trim()
        || typeof name !== "string" || !name.trim();
}
function dshaReadOnlyToolHistory(block) {
    // Render retained data as text. No typed card or action callbacks are created here.
    const retained = [block.argsRaw ?? block.call?.argsRaw];
    for (const item of Array.isArray(block.content) ? block.content : []) {
        if (item?.type === "text") retained.push(item.text);
    }
    const text = retained.filter(value => typeof value === "string").join("\\n");
    return (0, react_jsx_runtime.jsx)("pre", { "data-dsha-invalid-tool-history": true,
        style: { whiteSpace: "pre-wrap", overflowWrap: "anywhere" },
        children: text ? "[DSHA_INVALID_TOOL_CALL]\\n" + text : "[DSHA_INVALID_TOOL_CALL]" });
}
function callName(call) {
    return dshaToolHistoryInvalid(call.block) ? "DSHA_INVALID_TOOL_CALL" : dshaToolHistoryName(call.block);
}'''),
      (generic_card, generic_card + 'if(dshaToolHistoryInvalid(block))return dshaReadOnlyToolHistory(block);'),
      (call_row, '''const invalidIdentity = dshaToolHistoryInvalid(call.block);
    const autoReviewDenied = (0,react.useMemo)(()=>!invalidIdentity&&call.phase===`result`&&toolRowModel(toolName,call.block).autoReviewDenial!==null,[invalidIdentity,toolName,call]);
    if (invalidIdentity) return dshaReadOnlyToolHistory(call.block);'''),
      ('''const call=(0,react.useMemo)(()=>toolCallPhase(block),[block]);return(0,react_jsx_runtime.jsx)(ToolCall,{renderSlot,callId:call.block.callId,toolName:callName(call),call,openFile,renderFallback,cwd,home,inspectCall,useDisclosure,loadImage,children:call.phase!==`preparing`&&call.block.subCalls.length>0?(0,react_jsx_runtime.jsx)(`div`,{className:ToolCallTree_module_css_default.subCalls,"data-subcalls":true,children:call.block.subCalls.map(child=>(0,react_jsx_runtime.jsx)(ToolCallBranch,{renderSlot,block:child,cwd,home,openFile,renderFallback,inspectCall,useDisclosure,loadImage},child.callId))}):null})''',
       '''const call = (0, react.useMemo)(() => toolCallPhase(block), [block]);
    const children = call.phase !== "preparing" && block.subCalls.length > 0
        ? (0, react_jsx_runtime.jsx)("div", {
            className: ToolCallTree_module_css_default.subCalls,
            "data-subcalls": true,
            children: block.subCalls.map((child, index) => (0, react_jsx_runtime.jsx)(ToolCallBranch, {
                renderSlot, block: child, cwd, home, openFile, renderFallback, inspectCall, useDisclosure, loadImage
            }, dshaToolHistoryInvalid(child) ? "invalid:" + index : "call:" + child.callId))
        }) : null;
    if (dshaToolHistoryInvalid(block)) {
        const history = dshaReadOnlyToolHistory(block);
        // Descendants were projected separately and pass through this same identity check.
        return children === null ? history : (0, react_jsx_runtime.jsxs)(react_jsx_runtime.Fragment, { children: [history, children] });
    }
    return (0, react_jsx_runtime.jsx)(ToolCall, {
        renderSlot, callId: block.callId, toolName: callName(call), call, openFile,
        renderFallback, cwd, home, inspectCall, useDisclosure, loadImage, children
    });''')],
      previous=[(call_name, previous_display)])

if __name__ == '__main__': main()

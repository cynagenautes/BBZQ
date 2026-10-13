"""Structural evidence for native monitoring, page read boundaries, and manual paste review."""
from collections import Counter, defaultdict
import re

FRAMEWORK_OWNERS = ("Landroid/content/ClipboardManager;->", "Landroid/text/ClipboardManager;->")


def signature(method):
    match = re.fullmatch(r".+;->([^()]+)\((.*?)\)(.+)", method)
    if not match:
        return None
    name, parameters, returns = match.groups()
    params = re.findall(r"\[*L[^;]+;|\[*[ZBCSIJFD]", parameters)
    return name, params, returns


def inventory(index):
    api_calls = [c for c in index["calls"] if c["target"].startswith(FRAMEWORK_OWNERS)]
    method_strings = defaultdict(set)
    for item in index["strings"]:
        method_strings[item["method"]].add(item["value"])
    readers = {c["caller"] for c in api_calls if "->getPrimaryClip(" in c["target"]}
    candidates = {role: set() for role in ("WebDispatch", "Mall", "OuroEditor", "MiniGame", "PlayerRpc")}
    for method, values in method_strings.items():
        shape = signature(method)
        if not shape:
            continue
        name, params, returns = shape
        if {"getClipboardData", "setClipboardData"} <= values and returns == "Ljava/lang/String;" and len(params) == 4 and params[:3] == ["Ljava/lang/String;"] * 3:
            candidates["WebDispatch"].add(method)
        if "getClipboardContent_MallWebFragmentV2" in values and name == "run" and not params and returns == "V":
            candidates["Mall"].add(method)
    for method in readers:
        name, params, returns = signature(method)
        if method.startswith("Lkntr/common/ouro/core/plugin/pasteboard/OuroPasteboardPlugin$") and name == "invokeSuspend" and params == ["Ljava/lang/Object;"]:
            candidates["OuroEditor"].add(method)
        if returns == "V" and len(params) == 3 and params[0].endswith("/UpdateClipboard$Request;"):
            candidates["PlayerRpc"].add(method)
    for call in index["calls"]:
        name, params, returns = signature(call["caller"])
        if call["caller"].startswith("Lcom/bilibili/lib/fasthybrid/game/engine/ability/impl/clipboard/ClipboardAbility$handleGetClipboardData$") and name == "invokeSuspend" and params == ["Ljava/lang/Object;"]:
            candidates["MiniGame"].add(call["caller"])
    return {
        "schemaVersion": 1,
        "frameworkApiCounts": dict(sorted(Counter(signature(c["target"])[0] for c in api_calls).items())),
        "frameworkApiCalls": api_calls,
        "listenerImplementations": index.get("clipboardListenerClasses", []),
        "searchInputs": index.get("searchInputClasses", []),
        "exactReflectionApiStrings": [s for s in index["strings"] if s["value"] in {
            "getPrimaryClip", "hasPrimaryClip", "getPrimaryClipDescription", "addPrimaryClipChangedListener", "removePrimaryClipChangedListener",
        }],
        "pageReadBoundaries": {role: {"status": "unique" if len(methods) == 1 else "missing" if not methods else "ambiguous", "methods": sorted(methods)} for role, methods in candidates.items()},
        "manualReviewRequired": True,
        "runtimeValidated": False,
    }


def compare(before, after):
    def edges(data):
        return {c["caller"] + " => " + c["target"] for c in data["frameworkApiCalls"]}
    return {
        "addedApiEdges": sorted(edges(after) - edges(before)),
        "removedApiEdges": sorted(edges(before) - edges(after)),
        "listenerImplementationsChanged": before["listenerImplementations"] != after["listenerImplementations"],
        "pageReadBoundariesChanged": before["pageReadBoundaries"] != after["pageReadBoundaries"],
    }

# Accessibility Tree 巨大化問題の改善案

## 現状の問題

| 指標 | 実測値 |
|------|--------|
| ダンプサイズ | **42KB** (1行JSON) |
| ノード数 | 50〜200+ |
| ツリー深さ | 15+ 階層 |
| LLMトークン消費 | ~10,000 tokens/回 |

LLMにとっての本質的な問題：
- **大量のノイズ**: `naf=true` の空コンテナ、`text=""` のレイアウトノード等が大半
- **座標計算の負荷**: boundsの4値からタップ位置を計算する必要がある
- **重複ID**: 同一 `resourceId` が多数あり、テキストで区別するしかない

---

## 案A: サーバー側でフラット要約を返す（推奨）

`get_ui_dump` に `format` パラメータを追加し、LLM向けのフラット要約を返すモードを作る。

### 出力例

```
Screen: 1080x2400 | App: com.android.certification.niap.permission.dpctester
───────────────────────────────────────────────
[0] ImageButton "前に戻る" tap(73,205) clickable
[1] TextView "Test Suite Settings" at(418,205)
[2] TextView "signature_test_suite" at(540,367) [header]
[3] CheckBox "Runtime Permissions" tap(540,484) clickable checked=false
[4] CheckBox "Signature" tap(540,625) clickable checked=false
[5] CheckBox "Pie(9)" tap(540,766) clickable checked=false
[6] CheckBox "Q(10)" tap(540,907) clickable checked=false
[7] CheckBox "R(11)" tap(540,1048) clickable checked=false
[8] CheckBox "Snow Corn(12)" tap(540,1189) clickable checked=false
[9] CheckBox "Tiramisu(13)" tap(540,1330) clickable checked=false
[10] CheckBox "UDC(14)" tap(540,1471) clickable checked=false
[11] CheckBox "VIC(15)" tap(540,1612) clickable checked=false
[12] CheckBox "Baklava(16)" tap(540,1753) clickable checked=true ★
[13] CheckBox "CinnamonBun(17)" tap(540,1894) clickable checked=true ★
[14] CheckBox "BIND_* Permissions" tap(540,2035) clickable checked=false
[15] TextView "install_test_module" at(540,2194) [header]
[16] TextView "non_platform_test_module" at(540,2320) [header]
──────────────────────── scrollable: RecyclerView ─
```

### メリット
- **42KB → ~1KB** にサイズ削減（95%以上）
- 座標が **事前計算済み** (`tap(x,y)`) なので LLM は数値をそのまま使える
- `clickable` / `checked` 等のフラグが一目でわかる
- 空のコンテナノードが完全に排除される

### デメリット
- 生の階層構造が失われる（必要なら `format=json` で従来形式も取得可能）
- 要約ロジックのメンテナンスが必要

### 実装イメージ

```kotlin
// McpSseServer.kt の get_ui_dump に format パラメータ追加
val format = args["format"]?.jsonPrimitive?.contentOrNull ?: "json"

val result = if (format == "summary") {
    val dump = adbObserver.dumpMuttonAgent(false, 2)
    UiDumpSummarizer.summarize(dump) // 新規クラス
} else {
    adbObserver.dumpMuttonAgent(includeImage, quality)
}
```

```kotlin
// UiDumpSummarizer.kt (新規)
object UiDumpSummarizer {
    fun summarize(jsonDump: String): String {
        val root = Gson().fromJson(jsonDump, JsonObject::class.java)
        val sb = StringBuilder()
        var index = 0
        
        fun walk(node: JsonObject) {
            val text = node.get("text")?.asString ?: ""
            val desc = node.get("contentDescription")?.asString ?: ""
            val className = node.get("className")?.asString?.substringAfterLast(".") ?: ""
            val clickable = node.get("clickable")?.asBoolean ?: false
            val label = text.ifEmpty { desc }
            
            // テキストやcontentDescriptionがあるノード、またはclickableなノードのみ出力
            if (label.isNotEmpty() || clickable) {
                val bounds = node.getAsJsonObject("bounds")
                val cx = (bounds.get("left").asInt + bounds.get("right").asInt) / 2
                val cy = (bounds.get("top").asInt + bounds.get("bottom").asInt) / 2
                val action = if (clickable) "tap($cx,$cy) clickable" else "at($cx,$cy)"
                sb.appendLine("[$index] $className \"$label\" $action")
                index++
            }
            
            node.getAsJsonArray("children")?.forEach { child ->
                walk(child.asJsonObject)
            }
        }
        walk(root)
        return sb.toString()
    }
}
```

### 実装コスト: **中** (新規クラス1つ + パラメータ追加)

---

## 案B: depth パラメータの追加（最も簡単）

`get_ui_dump` に `max_depth` パラメータを追加し、浅い階層のみを返す。

```json
{ "max_depth": 3 }
```

→ ルートから3階層まで。子ノードが省略された場合は `"children_count": 5` で示す。

### メリット
- 実装が簡単（Agent側のダンプ取得時にtruncateするだけ）
- 段階的に深掘りできる（まず全体構造、次にターゲット領域をdeepに）

### デメリット
- 深い位置のボタンやテキストが見えない（Android UIは平均8階層以上）
- LLMが2回呼ぶ必要があるケースが増える

### 実装コスト: **低**

---

## 案C: 高レベルAPI の追加（根本解決）

座標計算をLLMに任せるのではなく、テキストベースの操作APIを追加する。

```
tap_text(text="Runtime Permissions")     → テキストを含むノードの中心をタップ
scroll_to_text(text="CinnamonBun(17)")   → テキストが見つかるまでスクロール
find_and_tap(resource_id="android:id/checkbox", index=3)  → N番目の要素をタップ
```

### メリット
- LLMが座標を一切知らなくてよい
- スクロール＋タップの複合操作が1コールで完結
- 最も失敗しにくい

### デメリット
- Agent (Mutton Agent) 側の実装変更が必要
- テキストが曖昧な場合のフォールバックロジックが複雑

### 実装コスト: **高** (Agent側のUiAutomation改修が必要)

---

## 推奨の組み合わせ

| フェーズ | 施策 | 効果 |
|----------|------|------|
| **Phase 1** | 案A (フラット要約) | トークン95%削減、座標事前計算 |
| **Phase 2** | 案C の `tap_text` のみ先行実装 | 最頻出操作の成功率向上 |
| Phase 3 | 案C の `scroll_to_text` 等 | スクロール不要に |

Phase 1 はサーバー側(JVM)だけの変更で完結するため、Agent再デプロイ不要です。

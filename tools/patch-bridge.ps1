$f = 'F:\TCM\mods\ctdweb\src\main\java\top\hibermod\ctdweb\WebBridge.java'
$t = [IO.File]::ReadAllText($f, [Text.Encoding]::UTF8)

if ($t -match 'TaskBroker broker') { Write-Host "已接入"; exit 0 }

$t = $t.Replace(
    '    private final AtomicReference<String> latestResponse = new AtomicReference<>();',
    "    private final AtomicReference<String> latestResponse = new AtomicReference<>();`r`n    private final TaskBroker broker = new TaskBroker();")

$t = $t.Replace(
    '    public String latestResponse() {',
    "    public TaskBroker broker() {`r`n        return broker;`r`n    }`r`n`r`n    public String latestResponse() {")

$t = $t.Replace(
    '                        case 0x1 -> acceptResponse(f.text());',
    '                        case 0x1 -> handleText(f.text());')

$helper = @'
        /** 回执与任务结果走同一个通道，靠 JSON 里的 taskResult 字段区分。 */
        private void handleText(String text) {
            String trimmed = text.trim();
            if (trimmed.contains("taskResult")) {
                String id = extract(trimmed, "id");
                boolean ok = trimmed.contains("\"ok\":true");
                String payload = extract(trimmed, "payload");
                broker.complete(id, ok, payload, 0);
            } else {
                acceptResponse(text);
            }
        }

        /** 极简取值：抓 "key":"value" 的第一个字符串值。够用且不引依赖。 */
        private String extract(String json, String key) {
            int k = json.indexOf("\"" + key + "\"");
            if (k < 0) return "";
            int colon = json.indexOf(':', k);
            if (colon < 0) return "";
            int open = json.indexOf('"', colon + 1);
            if (open < 0) return "";
            int end = json.indexOf('"', open + 1);
            return end < 0 ? "" : json.substring(open + 1, end);
        }

        synchronized void sendText(byte[] text) {
'@
$t = $t.Replace("        synchronized void sendText(byte[] text) {", $helper)

[IO.File]::WriteAllText($f, $t, (New-Object Text.UTF8Encoding($false)))
Write-Host "WebBridge 已接入 TaskBroker"

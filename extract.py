import json
import base64
import subprocess

out = subprocess.check_output(
    ['./scripts/mcp_call.sh', 'get_ui_dump', '{"include_image":true, "image_quality":2}'],
    text=True
)

for line in out.splitlines():
    if line.startswith("data: "):
        payload = line[6:]
        try:
            data = json.loads(payload)
            if "result" in data and "content" in data["result"]:
                text_str = data["result"]["content"][0]["text"]
                inner = json.loads(text_str)
                b64 = inner.get("screenshot")
                if b64:
                    with open('/Users/wkouki/.gemini/jetski/brain/b502dc47-bda3-42bb-9f92-47ce41197557/artifacts/screenshot_q2.jpg', 'wb') as f:
                        f.write(base64.b64decode(b64))
                    print("SUCCESS")
        except Exception as e:
            continue

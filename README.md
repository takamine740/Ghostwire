# Ghostwire

Ghostwire is a Burp Suite extension. It acts on traffic that is in Burp's target scope. The model is the one saved on the Settings tab. The API key stays in Burp's user preferences on this machine.

## Build the JAR

Java 21 is required. From the project root:

```powershell
.\gradlew.bat jar
```

Gradle writes `build\libs\ghostwire-0.1.0.jar`. That file includes the extension classes and Gson. The Montoya API is not packed in, because Burp provides it.

In Burp, open **Extensions → Installed → Add**, set the extension type to **Java**, and select that JAR. Burp loads `com.ghostwire.Extension`.

## Guide

Open one function at a time.

### Settings and connection

Settings stores the connection to the model and the text that each function sends. The API key is kept in Burp's user preferences on this machine. It is not written into the project file.

1. Open the Settings tab.
2. Paste a Bedrock API key. A short-term key expires. Paste a new one in the same field when Test connection reports that the token is expired.
3. Set the region and copy the model ID from the Bedrock console. A model ID that starts with `xai.` is sent to the Mantle endpoint. Any other ID uses the Converse API.
4. Set max tokens and max input characters. Max input characters is the amount of request and response text included in one call.
5. If traffic to Bedrock must pass through an HTTP proxy, fill in the proxy host and port. Leave the port at 0 when there is no proxy.
6. Turn on redaction when Authorization, Cookie, and token values should be replaced with placeholders before they are sent.
7. Leave the passive-scan checkbox off until you want that function. It is off until you save it on.
8. Use the Prompt list to edit System, Analyze, Intruder, Repeater, Passive, or Report. Save stores the text. Reset prompt restores the bundled wording for the prompt currently on screen.
9. Click Test connection. The status area shows the model's short reply and the token counts.

Example: region `us-west-2`, model ID `xai.grok-4.3`, max tokens 4096, proxy port 0. Click Test connection. The status line begins with "Connected." and quotes a short reply. After that, Analyze, Intruder, Repeater, passive scan, and Report can call the model.

### Analyze with AI

Analyze sends one in-scope request and its response to the model and shows the answer on the AI tab. The call uses the Analyze prompt. The system prompt receives the in-scope targets. Text is cut to the max input size, and secrets are replaced when redaction is on.

1. Put the target in Burp's suite scope.
2. Save a working connection on the Settings tab.
3. In Proxy history, Repeater, or the site map, right-click one request that has a response.
4. Choose **Ghostwire: Analyze with AI**.
5. The AI tab opens. The answer is a session in the list on the left. Select it to read the full text.
6. A request outside scope is skipped. The status line says so.

Example: scope includes `https://app.example.com`. In Proxy, right-click `GET https://app.example.com/login` and choose **Ghostwire: Analyze with AI**. The AI tab shows a session for that URL. The answer names what the exchange shows, what is only suspected, and what to try next in Repeater.

### Follow-up chat

Follow-up chat continues the selected analysis. The model still has that session's exchange and the earlier answers. Each session is stored in the Burp project.

1. On the AI tab, select a session in the list.
2. Type the question in the box under the transcript.
3. Click **Send follow-up**, or press Ctrl+Enter.
4. Wait for the status line to clear. The new answer is appended to the same session.
5. Select another session to ask about a different exchange. The box stays disabled until a session is selected.

Example: after the login analysis, select that session and send: "Which response header is missing, and what is a Repeater check for it?" The reply stays attached to `https://app.example.com/login`. A later question about `/account` belongs in the session created from that other request.

### Intruder payloads

**Ghostwire: AI payloads** asks the model once for a list of values for the marked Intruder position. Burp then places those values one at a time. The request must be in scope. An out-of-scope attack produces an empty list.

1. Send an in-scope request to Intruder.
2. Clear the automatic payload markers and highlight the value you want to vary. That highlighted value is the base value the model sees, along with a short stretch of the request around it.
3. Open the Payloads tab. Set the payload type to **Extension-generated**.
4. Choose the generator **Ghostwire: AI payloads**.
5. Start the attack. The extension log records how many values were returned.
6. When the list is finished, Intruder moves on. Ghostwire does not add a final empty payload.

Example: the request is `POST https://app.example.com/search` with body `q=guest`. Highlight `guest`. Payload type is Extension-generated, generator **Ghostwire: AI payloads**. Start attack. Intruder sends one request per returned line, such as a quote, a boolean fragment, or a long string, in the `q` parameter.

### Repeater loop

The Repeater loop asks the model for one next request, shows it, and sends it only after the rules below pass. The host stays the host of the request you started from. The URL must stay in scope. Redirects are not followed. The methods GET, HEAD, OPTIONS, POST, PUT, DELETE, and PATCH are the ones that can be sent. Each send is written to the audit log in the project.

1. Right-click an in-scope request and choose **Ghostwire: Repeater loop**. The Repeater tab opens.
2. Read the proposed request and the reason above it.
3. Leave **Approve each request before it is sent** on for the first runs. GET, HEAD, and OPTIONS then wait for **Send this request**. POST, PUT, DELETE, and PATCH always wait, even when that checkbox is off.
4. Click **Send this request** to send the proposal, or **Skip** to ask for a different one.
5. Max requests is 1 to 20. The default is 5. Seconds between sends is 0 to 30. The default is 1.
6. Click **Stop loop** to cancel the task. One loop runs at a time.
7. **Export audit log** writes `ghostwire-repeater-audit.txt` for the rows currently stored.

Example: start from `GET https://app.example.com/search?q=guest`. The model proposes the same host with `q` set to a quote character. The request stays on `app.example.com`, uses HTTP/1.1, and is not sent until you click **Send this request**. The audit table records the time, result, method, URL, status, and reason. **Stop loop** ends the run before the max of 5 is reached.

### Passive scan

When the checkbox is on, Burp's passive scan can ask the model to review an in-scope text response. Ghostwire does not send its own request. The same response body is reviewed once. A finding is added as an issue whose name starts with `Ghostwire:`. Confirm it in the request and response before you treat it as a result.

1. On Settings, turn on **Passive scan: review in-scope text responses during a Burp passive scan** and click Save.
2. Keep the target in scope.
3. Run a Burp passive scan or an audit that includes passive checks. In the scan launcher, leave the Ghostwire extension check enabled.
4. Open the issue list. Ghostwire issues are named `Ghostwire:` followed by the observation.
5. Turn the checkbox off and save when you want Burp scans to skip this review. The default is off.

Example: `https://app.example.com` is in scope and the checkbox is saved on. You audit one Proxy history item for `GET https://app.example.com/account`. The response is HTML. If the model reports a missing cookie flag, the issue list gains an item such as "Ghostwire: Cookie without Secure flag" on that URL. A second look at the same response body does not call the model again.

### Issue triage and report

Triage reads scanner issues that are in scope and drafts a short report: confirmed, downgraded, or rejected, with a recommended severity, a note, reproduction steps, and remediation. The scanner's original issues stay as they are. One pass sends at most 15 issues. **Add downgraded issues** creates a separate Ghostwire issue only when the recommended severity is lower than the scanner severity.

1. On the Report tab, click **Load in-scope issues**. The table fills from the site map. Or, in the issue list, select issues, right-click, and choose **Ghostwire: Triage issues**. The Report tab opens and drafts those issues.
2. Select the rows to review. **Draft report** sends the selection, up to 15 issues.
3. Read the draft in the lower pane.
4. Click **Add downgraded issues** when you want the lower-severity notes written as separate issues.
5. **Export report** writes `ghostwire-triage.txt`.
6. **Stop** cancels a draft that is still running. One draft runs at a time.

Example: the site map has a High "SQL injection" issue on `https://app.example.com/search?q=test`. Select that row and click **Draft report**. The draft says the scanner severity is High and the recommended severity is Information because the response is the unchanged search page. The original High issue is still in Burp. **Add downgraded issues** adds a separate Ghostwire issue for that lower recommendation.

### Technology inventory

The Technologies tab records products seen in in-scope traffic from Proxy, Repeater, crawl, and active scan. Crawl traffic is labeled Scanner, because that is the tool name Burp reports. The check uses response headers, cookie names, known paths, version tags, and script or stylesheet addresses. It does not call the model and does not request a file on another host. A script or stylesheet address in an in-scope page is stored on that page's host, and the evidence is the address. The version and package URL are filled in when the page states the version. Category separates a CDN from the application. Supplier, license, and file hash stay empty. Rows are saved in the project.

1. Put the site in scope and browse it, or run a crawl or an active scan.
2. Open the Technologies tab. New rows appear as responses are seen.
3. Type in Filter to match the product, version, category, package URL, host, source, evidence, or URL.
4. **Export CSV** writes the rows that match the filter to `ghostwire-technologies.csv`.
5. A later response that contains a version fills a row that had none. A different version for the same product and host is a second row.
6. To record another product, add a rule under `fingerprints/rules.json` and rebuild the extension. A hashed file name such as `app.8f3a.js` produces no row when the address does not name a product and a version.

Example: `https://app.example.com` is in scope. The home page response has header `x-amz-cf-pop` and `<meta name="ajs-version-number" content="9.12.5">`, and the HTML also contains `com.atlassian.jira`. The table gains Amazon CloudFront, category cdn, with an empty version, and Jira 9.12.5, category application, with package URL `pkg:maven/com.atlassian.jira/jira-core@9.12.5`. A script tag pointing at `https://cdn.example.net/jquery.min.js?ver=3.6.0` adds jQuery 3.6.0 on the host `app.example.com`. The evidence is that script address.

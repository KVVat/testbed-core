# How to run MDSCert test case on TestBed Core 

## Setup & Execution Flow

### 1. Download Artifacts
Get the latest TestBed Core release distributable and the test package ZIP from the following GitHub releases page:
https://github.com/KVVat/testbed-core/releases/tag/PR5

- **Application Bundle:** `TestbedCore-[your-os].zip`
- **Test Package:** `plugins-and-resources.zip`

### 2. Install TestBed Core
Extract the `TestbedCore-[your-os].zip` archive and follow the instructions in the `README.md`. Necessary SDK tools (such as ADB) will be downloaded and set up automatically during the initialization.
- **macOS:** Refer to `README.md` to bypass gatekeeper quarantine attributes if needed.
- **Windows:** You must launch the tool using the included `.bat` launcher file for the initial run. Due to batch script path handling, ensure that the extraction path **does not contain spaces**.

### 3. Connect Device
Launch TestBed Core and connect your target Android device via USB/ADB. The connection status will be displayed in the upper-right status indicator panel.
- If it does not connect, verify that USB Debugging is enabled on the device and that you have accepted the "Allow USB Debugging" dialog prompt on the Android screen.
- A Logcat monitor window may open automatically. This is for general debugging and is not required for running tests; you can safely close it. This can also be configured to not start automatically in the settings.

### 4. Import the Test Package
Open the hamburger menu in the upper-left corner and click on **Test Explorer**. Click the **Import** button. A file picker dialog will open; select the downloaded `plugins-and-resources.zip` package. This imports and populates the MDSCert test suites.

### 5. Run Test Cases
Select the desired test suite from the explorer list. Each test suite corresponds to a specific test class. You can click the **[Run all]** button to execute all cases or click individual play buttons. Execution will start immediately if the device status is green.

### 6. Verify Results
The progress bar in the main panel will indicate the live execution state. Once the test run is finished, you can view the detailed HTML test report directly by clicking the **Results** button in the Test Explorer.

---

## Important Prerequisites & Limitations

- **CA Certificate Import (Critical):**
  To pass Network/X.509/OCSP-related validation tests, you must manually trust the mock root CA certificates in the device system store:
  1. Extract `plugins-and-resources.zip` on your host PC and push the CA certificate files located inside the `resources/revocation/` directory (e.g., `root-ca.crt`, `cnsa/root-ca.crt`, `ecdsa/root-ca.crt`, etc.) to the device storage.
  2. On the Android device, go to `Settings -> Security -> Encryption & credentials -> Install a certificate -> CA certificate` and install all target CA certificates.
- **Disable Play Protect / Package Verification:**
  To allow automated sideloading of test apps in the background, you must disable package verification. Run the following adb command on your host terminal:
  ```bash
  adb shell settings put global package_verifier_enable 0
  ```
  Alternatively, toggle off the Play Protect scanning option in the Google Play Store settings.
- **Packet Capture Capabilities:**
  Tests that capture network traffic (such as `FcsTlscExtTest`) execute `tcpdump` commands under root authority on the device. Ensure that your target device is correctly rooted (e.g., using a `userdebug` OS build).
- **Recommended Host OS:**
  Network verification tests require spawning local SSL/TLS servers (`openssl` / `s_server`) on the host PC. Thus, using **macOS** or **Ubuntu (Linux)** as the host environment is highly recommended.

---

## Test Infrastructure & Modification

- **Execution Model:**
  Testbed Core executes tests by spawning host-side JUnit suites, copying target APKs and configuration properties to the device via ADB, launching the test client app, and tracking the results by observing logcat streams.
- **Network Simulation & Mocking:**
  Network tests set up virtual mock endpoints on the host machine and route device traffic through ADB reverse port forwards. The test suite definitions and testing APKs are managed under the `testbedui-plugins` subproject repository:
  https://github.com/KVVat/testbedui-plugins
  If you need to edit test configurations or rebuild APKs, open the plugins project and run the packaging task:
  ```bash
  ./gradlew zipPluginsAndResources
  ```

---

## TestBed Core Features

- **LLM-Driven Automation:**
  TestBed Core supports self-contained loops with AI assistants using the Model Context Protocol (MCP). If you open the project in an MCP-equipped workspace and ask the assistant questions regarding test failures or code, it can analyze local logs and self-heal configuration errors.
- **Built-in ToolBox:**
  Exposes real-time Logcat viewer, system File Explorer (supporting root file extraction/pull), and dynamic UI dump tree inspector.
- **MCP Configuration:**
  Configuring the local Stdio MCP bridge allows LLMs to control Android terminals natively. See the TestBed Core [README.md](https://github.com/KVVat/testbed-core) for detailed instructions.

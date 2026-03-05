# Stashed Ideas for testbed-core

## 1. Test Report Generation and Evidence Packing
While testing is handled by `testbedui-plugins`, `testbed-core` could provide a built-in HTTP server or a Compose UI dashboard to visualize the execution results.
- **XML Integration:** Leverage existing `AntXmlListener` (or similar JUnit reporting) to parse test results.
- **Evidence Linking:** Correlate test suite outputs (XML) with captured CC evidence (UI dumps, screenshots, logcat, pcap) to present it in a human-readable format for auditors.
- **Status:** Low priority for now. The focus is on establishing the core agent routing and test execution pipelines first.

## 2. Interactive Audit Interface (RAG)
Implement an AI-driven chat within the `testbed-core` UI.
- Allow users to query MDFPP documentation (from the `testbed-docs` submodule) and validate test failures against specific security requirements directly within the tool.

## 3. Live Evidence Stream
- A real-time view of the test execution, syncing the Android UI dumps and the active test code line being executed, making debugging and AI observation much easier.

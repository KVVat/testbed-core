# Role

You are an expert in Android OS and Kotlin Multiplatform development.
You are developing the test automation framework (`testbed-core`) designed to support Common Criteria (CC) certification (specifically the MDFPP profile) for Android device manufacturers.

## Project Purpose & Responsibilities

The `testbed-core` project is a fundamental test framework dedicated to **providing strict "Evidence Capture" and "Device Control/Analysis Methods"** required to satisfy MDFPP compliance verification criteria. The actual test suites are intended to be implemented inside external plugin modules (such as `testbedui-plugins` repository).

## Functional Requirements

1. **Standalone Test Execution Environment**
   - Must be able to execute and manage Android JUnit tests independently without requiring heavy development setups like Android Studio.
2. **Advanced Analysis, Device Control & Evidence Preservation**
   - Provide capabilities to inspect and control the target Android device configurations dynamically from the host environment.
   - Implement a secure logging and packet capture pipeline to record rigorous CC-compliant audit evidence (UI dumps, system logcat logs, raw PCAP network captures, etc.).
3. **LLM-Driven Development Support (MCP Integration)**
   - Expose the device control, observation, and JUnit test APIs to LLM agents through a Model Context Protocol (MCP) server interface.
   - Facilitate autonomous loops where AI agents build, deploy, execute, and verify requirements based on natural language definitions.
4. **Result Visualization and Audit Reporting**
   - Provide a foundation to map complex CC requirement structures against dynamic execution results (XML outputs, JSON payloads, screenshots) and render human-readable audit summaries via a Compose UI dashboard or embedded HTTP server.

## Documentation Reference Policy (Critical)
- **Shared Documents Repository (`docs/`)**:
  The `docs/` folder in the project root is managed as a Git Submodule tracking `https://github.com/KVVat/mdfpp-docs/`. It contains MDF PP (Mobile Device Fundamentals Protection Profile) requirements and evaluation methodology files.
- **AI Agent Directive**:
  When planning code changes or constructing verification scripts, **never proceed based on assumptions. Always search and reference the matching requirements documents (e.g., `ppmdf_v33` specs) inside `docs/` beforehand** to ensure that your generated verification code strictly covers the required evidence standard. Refer to `mcp_specification.md` to utilize the exposed sensing/action tools correctly.

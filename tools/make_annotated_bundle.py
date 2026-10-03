#!/usr/bin/env python3
"""
Create a teaching/learning bundle from the exact PINGS v0.14 source tree.

The goal is NOT to change how the program works. It copies the original
source untouched, then creates annotated copies with comments placed before
important classes, methods, configuration blocks, and data-flow sections.
"""
from __future__ import annotations

import re
import shutil
import sys
from pathlib import Path

SOURCE = Path(sys.argv[1]).resolve()
OUT = Path(sys.argv[2]).resolve()

if OUT.exists():
    shutil.rmtree(OUT)
OUT.mkdir(parents=True)

ORIGINAL = OUT / "ORIGINAL"
ANNOTATED = OUT / "ANNOTATED"
ORIGINAL.mkdir()
ANNOTATED.mkdir()

for item in SOURCE.iterdir():
    if item.name == ".git":
        continue
    dest = ORIGINAL / item.name
    if item.is_dir():
        shutil.copytree(item, dest)
    else:
        shutil.copy2(item, dest)

WINDOWS_NOTES = {
    "MainForm": "Main window constructor. It creates controls, tabs, event handlers, timers, and wires the whole Windows UI together.",
    "AddSwitchVlansTab": "Builds the Switch VLANs tab. It collects a management IP, SSH port, username, password, and platform choice, then displays read-only show-command output.",
    "DefaultGateway": "Walks active Windows network interfaces and returns the first IPv4 default gateway. This is a convenience guess; a gateway is not always the physical access switch.",
    "LoadSwitchVlansAsync": "Runs switch discovery without freezing the UI. It opens SSH, reads show version, picks VLAN commands, and returns usable VLAN output.",
    "RunSshCommand": "Runs one read-only CLI command over an established SSH connection and combines normal output with command error text.",
    "DetectSwitchVendor": "Uses show-version text to identify Cisco IOS/IOS-XE, Aruba CX, ArubaOS-Switch/ProCurve, or Nortel/Avaya ERS when Auto is selected.",
    "VlanCommands": "Returns the ordered show-command choices used for each switch family.",
    "LooksLikeUsefulVlanOutput": "Rejects obvious CLI errors and accepts output that looks like a VLAN table.",
    "StyleButton": "Applies one consistent visual style to Windows buttons.",
    "NewGrid": "Creates a DataGridView used by the result tabs and attaches row coloring plus the manual classification context menu.",
    "ApplyRowColors": "Paints responding devices green and non-responding/missing devices red; changed rows are bold.",
    "Clone": "Creates an independent DeviceRow copy so a Before baseline does not change when Current results change.",
    "AttachClassificationMenu": "Creates the right-click actions for device classification, custom groups, learned MAC prefixes, and clearing overrides.",
    "ApplyManualClassification": "Applies a user classification to one or many selected devices. Aruba classification can also learn the first 3 MAC bytes (OUI/prefix).",
    "MacPrefix": "Normalizes a MAC and returns its first 6 hex characters (first 3 bytes/OUI). Example 84:D4:7E:12:34:56 becomes 84D47E.",
    "FormatMacPrefix": "Formats a six-hex-character learned prefix back into human-friendly AA:BB:CC form.",
    "GetLearnedMacPrefix": "Looks for a stored vendor/type rule that matches the selected device's first 3 MAC bytes.",
    "ApplyLearnedPrefixesToCurrent": "Reclassifies currently displayed devices using learned MAC-prefix rules, while keeping explicit per-device overrides higher priority.",
    "ForgetLearnedMacPrefix": "Deletes learned prefix rules for selected devices and recalculates their automatic classifications.",
    "ClearManualClassification": "Removes explicit per-device overrides, then falls back to built-in OUI rules and learned-prefix rules.",
    "AssignCustomGroup": "Prompts for a group name and persists it for all selected devices.",
    "ClearCustomGroup": "Removes saved custom-group membership from selected devices.",
    "GetSelectedDevices": "Converts selected grid rows into DeviceRow objects and falls back to the current row when needed.",
    "PromptForText": "Small reusable modal dialog used to collect a custom group name.",
    "CandidateKeys": "Builds stable lookup keys in priority order: MAC, hostname, then IP.",
    "GetOverride": "Finds the first saved manual override matching MAC, hostname, or IP.",
    "SaveOverride": "Stores a manual device classification/group and writes it to the JSON override file.",
    "LoadOverrides": "Loads persistent manual device classifications from the PINGS device-overrides JSON file.",
    "PersistOverrides": "Serializes manual overrides to JSON so they survive application restarts.",
    "LoadLearnedMacPrefixes": "Loads learned OUI/prefix rules from the PINGS learned-mac-prefixes JSON file.",
    "PersistLearnedMacPrefixes": "Writes learned MAC-prefix classifications to JSON.",
    "CurrentSubnet": "Finds an active local IPv4 interface, combines IP plus mask, and returns CIDR notation such as 192.168.1.0/24.",
    "ScanAsync": "Core subnet scan. Expands the CIDR, pings addresses in parallel, resolves hostnames/MACs, applies vendor/type rules, and updates Current results.",
    "VendorFromMac": "Matches known first-3-byte MAC prefixes against the built-in Aruba/Cisco/Meraki and correction lists.",
    "Classify": "Assigns a broad device type using vendor data plus hostname clues. Learned/manual rules are applied elsewhere and can override this result.",
    "BuildDifferenceRows": "Compares Before vs After. Responding now but not before = NEW; responding before but not now = MISSING; identity/classification changes = CHANGED.",
    "ExportResults": "Shows Save As and writes Before, After, and Difference data as CSV or text.",
    "BuildCsvExport": "Builds a spreadsheet-friendly CSV with a Section column identifying BEFORE, AFTER, or DIFFERENCE.",
    "BuildTextExport": "Builds a human-readable tab-delimited report with before/after counts and all three result sections.",
    "Csv": "Escapes a field according to CSV rules by doubling embedded quotes and surrounding the result in quotes.",
    "BuildRows": "Creates the rows shown in the UI and adds NEW/MISSING/CHANGED markers when compare mode is enabled.",
    "RefreshViews": "Rebuilds every result tab from the current data and active filter.",
    "UpdateCounts": "Recalculates the summary buttons and before/after delta counts.",
    "IsLocalComputerIp": "Checks whether an address belongs to this Windows computer so the local machine can be auto-classified as PC/Desktop.",
    "IpToUInt": "Converts dotted IPv4 into an integer so results sort numerically instead of alphabetically."
}

ANDROID_NOTES = {
    "MainActivity": "Single Android activity that owns the UI, scan state, saved classifications, compare state, export workflow, and SSH VLAN dialog.",
    "onCreate": "Android entry point after the Activity is created. It programmatically builds the whole screen, then connects buttons, spinners, and listeners to actions.",
    "onActivityResult": "Receives the file chosen by Android's document picker and writes the pending CSV/TXT export to that URI.",
    "onDestroy": "Stops scheduled repeat scans and shuts down the worker thread pool when the Activity is destroyed.",
    "currentSubnet": "Uses ConnectivityManager and LinkProperties to locate a local IPv4 address and calculate its CIDR network.",
    "scan": "Core Android subnet sweep. Worker threads test reachability, resolve hostnames, try to learn a MAC from ARP, classify devices, then post results back to the UI thread.",
    "ipNumber": "Turns dotted IPv4 into a numeric value so addresses sort in real network order.",
    "macForIp": "Attempts to read /proc/net/arp for an IP-to-MAC mapping. Modern Android can restrict this, so blank MAC values are possible.",
    "candidateKeys": "Creates persistence keys in MAC, hostname, IP priority order.",
    "getOverride": "Reads a manual per-device classification/group from SharedPreferences.",
    "saveOverride": "Writes one explicit device classification/group to SharedPreferences and updates the in-memory row.",
    "clearOverride": "Deletes a device-specific override, then recalculates automatic and learned-prefix classification.",
    "showClassificationDialog": "Displays bulk classification choices for the selected devices.",
    "macPrefix": "Normalizes a MAC and returns its first 3 bytes (six hex characters) for learned vendor/type rules.",
    "formatMacPrefix": "Displays a learned prefix in AA:BB:CC form.",
    "getLearnedPrefix": "Looks up an OUI/prefix rule in the dedicated learned-prefix SharedPreferences store.",
    "learnAndClassifyAruba": "Marks selected devices as Aruba APs and learns each available first-3-byte MAC prefix so matching devices are classified automatically later.",
    "forgetLearnedPrefixes": "Deletes learned OUI/prefix rules selected by the user and refreshes affected device classifications.",
    "promptCustomGroup": "Collects and saves a custom group name for selected devices.",
    "vendorFromMac": "Built-in MAC-prefix vendor lookup used before learned/manual overrides.",
    "classify": "Heuristic device-type classification using vendor and hostname clues.",
    "buildDifferenceRows": "Creates NEW, MISSING, and CHANGED records by comparing the saved Before list with the latest scan.",
    "startExport": "Launches Android's Create Document picker and prepares either CSV or TXT content.",
    "buildCsvExport": "Creates a quoted CSV containing BEFORE, AFTER, and DIFFERENCE sections.",
    "buildTextExport": "Creates a readable text report with counts plus all three data sections.",
    "buildRows": "Builds screen rows and adds compare markers when compare mode is active.",
    "statusFilter": "Applies All/Pingable/No Ping filtering.",
    "visibleRows": "Applies the selected logical tab and then the status filter.",
    "renderAll": "Central UI refresh: updates count buttons, before/after deltas, selected tab styling, and the ListView.",
    "setList": "Creates the ListView adapter and formats each device row, including selected/up/down/change appearance.",
    "showSwitchVlansDialog": "Collects switch management address, SSH credentials, port, and platform. Credentials are used for the connection and are not stored by this code.",
    "defaultGateway": "Returns the IPv4 gateway from Android route information as a convenient starting address.",
    "loadSwitchVlans": "Runs SSH work on a background thread and displays read-only VLAN output after trying platform-specific show commands.",
    "runSshCommand": "Executes one command using JSch and returns stdout/stderr.",
    "detectSwitchVendor": "Identifies supported switch families from show-version output when Auto is selected.",
    "vlanCommands": "Returns candidate VLAN show commands by selected/detected platform.",
    "looksLikeUsefulVlanOutput": "Filters out obvious CLI error responses and accepts output that resembles a VLAN table.",
    "showSwitchOutput": "Shows switch command output in a selectable, scrollable monospaced dialog with Copy.",
    "dp": "Converts density-independent pixels to physical pixels for programmatic layouts.",
    "buttonLp": "Returns a consistent LayoutParams object for horizontally arranged buttons.",
    "fieldBackground": "Creates the rounded EditText background.",
    "raisedButton": "Creates the gradient/outlined button drawable used by action buttons.",
    "countButton": "Creates a colored summary/count button.",
    "lighten": "Produces a lighter RGB color for gradient button tops.",
    "darken": "Produces a darker RGB color for button borders.",
    "addTabButton": "Adds one custom tab button and changes currentTab when tapped.",
    "styleTabButtons": "Visually marks the active logical tab."
}

CS_CLASS_NOTES = {
    "DeviceRow": "Data model for one scanned IP/device. The UI binds directly to these public properties.",
    "ManualOverride": "Persisted vendor/type/group chosen by the user or learned from a prefix.",
    "MainForm": WINDOWS_NOTES["MainForm"]
}

KT_CLASS_NOTES = {
    "Dev": "Android data model for one device. copy(...) is used to create modified rows; change is mutable for compare markers.",
    "Override": "Small value holder for persisted vendor/type/group data.",
    "MainActivity": ANDROID_NOTES["MainActivity"]
}

def annotate_csharp(text: str) -> str:
    out = []
    for line in text.splitlines():
        cm = re.match(r'^(\s*)public sealed class\s+(\w+)', line)
        if cm:
            indent, name = cm.groups()
            note = CS_CLASS_NOTES.get(name, "Class " + name + " groups related application state and behavior.")
            out += [indent + "// =====================================================================",
                    indent + "// LEARNING NOTE: " + note,
                    indent + "// ====================================================================="]
        mm = re.match(r'^(\s*)(?:public|private|protected|internal)\s+(?:static\s+)?(?:async\s+)?[^=;{}]+?\s+(\w+)\s*\(', line)
        if mm:
            indent, name = mm.groups()
            note = WINDOWS_NOTES.get(name, "Method " + name + ": trace its parameters, local variables, state changes, and return value.")
            out += [indent + "// ---------------------------------------------------------------------",
                    indent + "// LEARNING NOTE: " + note]
        out.append(line)
    header = """// PINGS v0.14 - ANNOTATED WINDOWS SOURCE
// This file preserves the executable code and adds teaching comments.
// High-level data flow:
//   network/CIDR -> parallel ping -> host/MAC lookup -> automatic rules
//   -> learned OUI rules -> manual overrides -> UI tabs -> compare/export.
// Manual override priority intentionally beats learned-prefix and built-in rules.

"""
    return header + "\n".join(out) + "\n"

def annotate_kotlin(text: str) -> str:
    out = []
    for line in text.splitlines():
        dm = re.match(r'^(\s*)data class\s+(\w+)', line)
        cm = re.match(r'^(\s*)class\s+(\w+)', line)
        match = dm or cm
        if match:
            indent, name = match.groups()
            note = KT_CLASS_NOTES.get(name, name + " groups related Android data or behavior.")
            out += [indent + "// =====================================================================",
                    indent + "// LEARNING NOTE: " + note,
                    indent + "// ====================================================================="]
        fm = re.match(r'^(\s*)(?:override\s+)?(?:private\s+|public\s+|protected\s+)?fun\s+(\w+)\s*\(', line)
        if fm:
            indent, name = fm.groups()
            note = ANDROID_NOTES.get(name, "Function " + name + ": trace its inputs, state changes, and UI/network side effects.")
            out += [indent + "// ---------------------------------------------------------------------",
                    indent + "// LEARNING NOTE: " + note]
        out.append(line)
    header = """// PINGS v0.14 - ANNOTATED ANDROID SOURCE
// The original code is retained and teaching comments are inserted before
// important classes and functions.
// Scan/classification priority:
//   built-in vendor/hostname rule -> learned MAC prefix -> explicit manual override.
// Android can restrict ICMP-style reachability and ARP visibility.

"""
    return header + "\n".join(out) + "\n"

cs_src = SOURCE / "Pings.Windows" / "Program.cs"
cs_dest = ANNOTATED / "Pings.Windows" / "Program.annotated.cs"
cs_dest.parent.mkdir(parents=True, exist_ok=True)
cs_dest.write_text(annotate_csharp(cs_src.read_text(encoding="utf-8")), encoding="utf-8")
shutil.copy2(SOURCE / "Pings.Windows" / "Pings.Windows.csproj",
             ANNOTATED / "Pings.Windows" / "Pings.Windows.csproj")

kt_src = SOURCE / "app" / "src" / "main" / "java" / "com" / "jenkinskg" / "pings" / "MainActivity.kt"
kt_dest = ANNOTATED / "app" / "src" / "main" / "java" / "com" / "jenkinskg" / "pings" / "MainActivity.annotated.kt"
kt_dest.parent.mkdir(parents=True, exist_ok=True)
kt_dest.write_text(annotate_kotlin(kt_src.read_text(encoding="utf-8")), encoding="utf-8")

for rel in [
    "app/src/main/AndroidManifest.xml",
    "app/src/main/res/values/styles.xml",
    "app/build.gradle.kts",
    "build.gradle.kts",
    "settings.gradle.kts",
    ".github/workflows/build-windows.yml",
    ".github/workflows/build-android.yml",
    ".github/workflows/publish-windows-release.yml",
    ".github/workflows/publish-android-release.yml"
]:
    src = SOURCE / rel
    if src.exists():
        dst = ANNOTATED / rel
        dst.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(src, dst)

(OUT / "README_FIRST.md").write_text("""# PINGS v0.14 - Fully Annotated Learning Bundle

This bundle was generated from the exact v0.14 Git tag.

Folder layout:
- ORIGINAL: untouched source from the tag.
- ANNOTATED: teaching copies of the two main source files plus build files.
- CODE_WALKTHROUGH.md: how the major subsystems work.
- PROGRAMS_NEEDED.md: programs and runtimes needed to build and run.
- BUILD_INSTRUCTIONS.md: reproducible Windows and Android builds.

Start with:
1. ANNOTATED/Pings.Windows/Program.annotated.cs
2. ANNOTATED/app/src/main/java/com/jenkinskg/pings/MainActivity.annotated.kt

Comments beginning with LEARNING NOTE explain the role of important methods.
""", encoding="utf-8")

(OUT / "CODE_WALKTHROUGH.md").write_text("""# PINGS v0.14 Code Walkthrough

## Overall flow
CIDR subnet -> host enumeration -> parallel reachability tests -> hostname/MAC
identity -> automatic classification -> learned prefix -> manual override -> tabs
and filters -> Before/After compare -> export.

## Classification priority
Built-in rules are the default. Learned MAC-prefix rules can replace the
automatic answer. Explicit per-device manual overrides have the highest
priority.

## Before/After
Alive now but not before is NEW.
Alive before but not now is MISSING.
Alive in both but identity/classification differs is CHANGED.

## Windows
WinForms provides the UI. .NET networking APIs handle ping, interfaces and DNS.
SSH.NET supplies switch SSH access.

## Android
ConnectivityManager supplies local interface/route information. Worker threads
perform scanning. SharedPreferences stores classifications. Android may restrict
ARP visibility, so a blank MAC can occur.

## Switch VLANs
Credentials are used at run time and are not stored by this code. Auto mode
checks show version and then selects a read-only VLAN show command. Cisco
IOS/IOS-XE primarily uses show vlan brief.
""", encoding="utf-8")

(OUT / "PROGRAMS_NEEDED.md").write_text("""# Programs Needed for PINGS v0.14

Windows finished EXE:
- Windows 10/11 x64
- Network access to the target subnet
- No separate .NET runtime for the self-contained published EXE
- TCP/22 plus a read-only SSH account for Switch VLANs

Windows development/build:
- .NET 8 SDK
- Optional: Visual Studio 2022 with .NET desktop development, or VS Code
- NuGet access for SSH.NET / Renci.SshNet

Android finished APK:
- Android 8.0 or newer (minSdk 26)
- Network access
- TCP/22 plus an SSH account for Switch VLANs

Android development/build:
- JDK 17
- Android SDK / API 35
- Gradle 8.9
- Android Gradle Plugin 8.7.3
- Kotlin plugin 2.0.21
- JSch com.github.mwiede:jsch:0.2.21
- Optional: Android Studio
""", encoding="utf-8")

(OUT / "BUILD_INSTRUCTIONS.md").write_text("""# Building PINGS v0.14

Windows PowerShell from source root:

    dotnet restore .\\Pings.Windows\\Pings.Windows.csproj
    dotnet publish .\\Pings.Windows\\Pings.Windows.csproj -c Release -r win-x64 --self-contained true -p:PublishSingleFile=true

Output:
    Pings.Windows\\bin\\Release\\net8.0-windows\\win-x64\\publish\\Pings.exe

Android from source root:

    gradle assembleDebug

Output:
    app/build/outputs/apk/debug/app-debug.apk

For production Android distribution, configure a release signing key instead of
using the debug signing key.
""", encoding="utf-8")

print("Created annotated bundle at", OUT)

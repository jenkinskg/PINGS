using System.Collections.Concurrent;
using System.Net;
using System.Net.NetworkInformation;
using System.Net.Sockets;
using System.Runtime.InteropServices;
using System.Text.Json;
using System.Text;
using Renci.SshNet;

ApplicationConfiguration.Initialize();
Application.Run(new MainForm());

public sealed class DeviceRow
{
    public string IP { get; set; } = "";
    public string Hostname { get; set; } = "";
    public string MAC { get; set; } = "";
    public string Vendor { get; set; } = "";
    public string Type { get; set; } = "Unknown";
    public string Group { get; set; } = "";
    public string Status { get; set; } = "";
    public long LatencyMs { get; set; } = -1;
    public string Change { get; set; } = "";
}

public sealed class ManualOverride
{
    public string Vendor { get; set; } = "";
    public string Type { get; set; } = "Unknown";
    public string Group { get; set; } = "";
}

public sealed class MainForm : Form
{
    private readonly TextBox subnet = new() { Width = 150, Text = "192.168.1.0/24" };
    private readonly ComboBox filter = new() { Width = 115, DropDownStyle = ComboBoxStyle.DropDownList };
    private readonly ComboBox repeat = new() { Width = 105, DropDownStyle = ComboBoxStyle.DropDownList };
    private readonly Label summary = new() { AutoSize = true, Padding = new Padding(8, 8, 0, 0) };
    private readonly Label compareSummary = new() { AutoSize = true, Padding = new Padding(8, 8, 0, 0) };
    private readonly TabControl tabs = new() { Dock = DockStyle.Fill };
    private readonly Dictionary<string, DataGridView> grids = new();
    private readonly System.Windows.Forms.Timer repeatTimer = new();

    private readonly Button pingableBtn = new() { Text = "Pingable: 0", AutoSize = true, Name = "PingableCount" };
    private readonly Button apBtn = new() { Text = "APs: 0", AutoSize = true, Name = "APCount" };
    private readonly Button nonApBtn = new() { Text = "Non-APs: 0", AutoSize = true, Name = "NonAPCount" };
    private readonly Button noPingBtn = new() { Text = "Not Responding: 0", AutoSize = true, Name = "NoPingCount" };

    private List<DeviceRow> current = new();
    private List<DeviceRow> baseline = new();
    private bool compareMode = false;
    private readonly Dictionary<string, ManualOverride> manualOverrides = new(StringComparer.OrdinalIgnoreCase);
    private readonly string overrideFile = Path.Combine(
        Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData),
        "PINGS", "device-overrides.json");

    public MainForm()
    {
        Text = "PINGS Network Monitor v0.13";
        Width = 1360;
        Height = 820;
        MinimumSize = new Size(1050, 650);
        BackColor = Color.FromArgb(242, 245, 249);
        Font = new Font("Segoe UI", 9.5f);
        StartPosition = FormStartPosition.CenterScreen;

        filter.Items.AddRange(new object[] { "All", "Pingable", "No Ping" });
        filter.SelectedIndex = 0;
        repeat.Items.AddRange(new object[] { "Off", "30 sec", "5 min" });
        repeat.SelectedIndex = 0;
        LoadOverrides();

        var top = new FlowLayoutPanel
        {
            Dock = DockStyle.Top,
            Height = 118,
            WrapContents = true,
            AutoScroll = true,
            Padding = new Padding(14, 10, 14, 8),
            BackColor = Color.White
        };

        var title = new Label
        {
            Text = "PINGS  •  Network Availability Monitor",
            AutoSize = true,
            Font = new Font("Segoe UI Semibold", 14f),
            ForeColor = Color.FromArgb(35, 49, 66),
            Padding = new Padding(2, 5, 20, 5)
        };

        var currentSubnet = new Button { Text = "Current Subnet", AutoSize = true };
        var scan = new Button { Text = "Scan Now", AutoSize = true };
        var setBefore = new Button { Text = "Set Before", AutoSize = true };
        var compare = new Button { Text = "Compare Before/After", AutoSize = true };
        var export = new Button { Text = "Export Before/After", AutoSize = true };

        foreach (var b in new[] { currentSubnet, scan, setBefore, compare, export, pingableBtn, apBtn, nonApBtn, noPingBtn })
            StyleButton(b);

        scan.Font = new Font("Segoe UI Semibold", 9.5f);
        compare.Font = new Font("Segoe UI Semibold", 9.5f);

        top.Controls.AddRange(new Control[]
        {
            title,
            new Label { Text = "Subnet:", AutoSize = true, Padding = new Padding(0,8,0,0), ForeColor = Color.FromArgb(70,80,92) },
            subnet, currentSubnet, scan,
            new Label { Text = "Show:", AutoSize = true, Padding = new Padding(8,8,0,0), ForeColor = Color.FromArgb(70,80,92) },
            filter,
            new Label { Text = "Repeat:", AutoSize = true, Padding = new Padding(8,8,0,0), ForeColor = Color.FromArgb(70,80,92) },
            repeat,
            setBefore, compare, export,
            pingableBtn, apBtn, nonApBtn, noPingBtn,
            summary, compareSummary
        });

        foreach (var name in new[]
        {
            "All Pings", "Access Points", "Non-Access-Points",
            "PCs / Desktops", "Other Devices", "Unknown", "Custom Groups", "Missing / Changed"
        })
        {
            var page = new TabPage(name);
            var grid = NewGrid();
            grids[name] = grid;
            page.Controls.Add(grid);
            tabs.TabPages.Add(page);
        }

        AddSwitchVlansTab();

        tabs.Font = new Font("Segoe UI Semibold", 9.5f);
        tabs.Padding = new Point(14, 6);
        tabs.Appearance = TabAppearance.Normal;

        summary.Font = new Font("Segoe UI Semibold", 9f);
        summary.ForeColor = Color.FromArgb(42, 67, 101);
        compareSummary.Font = new Font("Segoe UI", 9f);
        compareSummary.ForeColor = Color.FromArgb(91, 101, 115);

        Controls.Add(tabs);
        Controls.Add(top);

        currentSubnet.Click += (_, _) => subnet.Text = CurrentSubnet();
        scan.Click += async (_, _) => await ScanAsync();
        setBefore.Click += (_, _) =>
        {
            baseline = current.Select(Clone).ToList();
            compareMode = false;
            RefreshViews();
            MessageBox.Show(
                $"Baseline saved. Pingable {CountPingable(baseline)}, APs {CountAPs(baseline)}, " +
                $"Non-APs {CountNonAPs(baseline)}, Not Responding {CountNoPing(baseline)}.");
        };
        compare.Click += (_, _) =>
        {
            compareMode = true;
            filter.SelectedItem = "All";
            RefreshViews();
            tabs.SelectedIndex = 7;
        };
        export.Click += (_, _) => ExportResults();
        filter.SelectedIndexChanged += (_, _) => RefreshViews();

        repeat.SelectedIndexChanged += (_, _) =>
        {
            repeatTimer.Stop();
            if (repeat.Text == "30 sec") repeatTimer.Interval = 30_000;
            else if (repeat.Text == "5 min") repeatTimer.Interval = 300_000;
            else return;
            repeatTimer.Start();
        };
        repeatTimer.Tick += async (_, _) => await ScanAsync();

        pingableBtn.Click += (_, _) => { filter.SelectedItem = "Pingable"; tabs.SelectedIndex = 0; };
        apBtn.Click += (_, _) => { filter.SelectedItem = "Pingable"; tabs.SelectedIndex = 1; };
        nonApBtn.Click += (_, _) => { filter.SelectedItem = "Pingable"; tabs.SelectedIndex = 2; };
        noPingBtn.Click += (_, _) => { filter.SelectedItem = "No Ping"; tabs.SelectedIndex = 0; };

        Shown += (_, _) => UpdateCounts();
    }

    private void AddSwitchVlansTab()
    {
        var page = new TabPage("Switch VLANs") { BackColor = Color.White };

        var controls = new FlowLayoutPanel
        {
            Dock = DockStyle.Top,
            Height = 92,
            WrapContents = true,
            Padding = new Padding(12, 10, 12, 8),
            BackColor = Color.FromArgb(242, 245, 249)
        };

        var host = new TextBox { Width = 155 };
        var port = new NumericUpDown { Width = 65, Minimum = 1, Maximum = 65535, Value = 22 };
        var user = new TextBox { Width = 125 };
        var password = new TextBox { Width = 125, UseSystemPasswordChar = true };
        var vendor = new ComboBox { Width = 155, DropDownStyle = ComboBoxStyle.DropDownList };
        vendor.Items.AddRange(new object[] { "Auto", "Cisco IOS / IOS-XE", "Aruba CX", "ArubaOS-Switch / ProCurve", "Nortel / Avaya ERS" });
        vendor.SelectedIndex = 0;

        var useGateway = new Button { Text = "Use Gateway", AutoSize = true };
        var showVlans = new Button { Text = "Show VLANs", AutoSize = true };
        var copy = new Button { Text = "Copy Output", AutoSize = true };
        foreach (var b in new[] { useGateway, showVlans, copy }) StyleButton(b);

        var output = new RichTextBox
        {
            Dock = DockStyle.Fill,
            ReadOnly = true,
            Font = new Font("Consolas", 10f),
            BackColor = Color.White,
            WordWrap = false
        };

        controls.Controls.AddRange(new Control[]
        {
            new Label { Text = "Switch IP/Host:", AutoSize = true, Padding = new Padding(0,8,0,0) }, host,
            useGateway,
            new Label { Text = "Port:", AutoSize = true, Padding = new Padding(4,8,0,0) }, port,
            new Label { Text = "User:", AutoSize = true, Padding = new Padding(4,8,0,0) }, user,
            new Label { Text = "Password:", AutoSize = true, Padding = new Padding(4,8,0,0) }, password,
            new Label { Text = "Type:", AutoSize = true, Padding = new Padding(4,8,0,0) }, vendor,
            showVlans, copy
        });

        useGateway.Click += (_, _) => host.Text = DefaultGateway();
        showVlans.Click += async (_, _) =>
            await LoadSwitchVlansAsync(host.Text.Trim(), (int)port.Value, user.Text, password.Text, vendor.Text, output, showVlans);
        copy.Click += (_, _) =>
        {
            if (!string.IsNullOrWhiteSpace(output.Text))
                Clipboard.SetText(output.Text);
        };

        page.Controls.Add(output);
        page.Controls.Add(controls);
        tabs.TabPages.Add(page);
    }

    private static string DefaultGateway()
    {
        try
        {
            return NetworkInterface.GetAllNetworkInterfaces()
                .Where(n => n.OperationalStatus == OperationalStatus.Up)
                .SelectMany(n => n.GetIPProperties().GatewayAddresses)
                .Select(g => g.Address)
                .FirstOrDefault(a => a.AddressFamily == AddressFamily.InterNetwork && !a.Equals(IPAddress.Any))
                ?.ToString() ?? "";
        }
        catch { return ""; }
    }

    private async Task LoadSwitchVlansAsync(
        string host, int port, string username, string password, string requestedVendor,
        RichTextBox output, Button button)
    {
        if (string.IsNullOrWhiteSpace(host) || string.IsNullOrWhiteSpace(username))
        {
            MessageBox.Show("Enter the switch IP/hostname and username.");
            return;
        }

        button.Enabled = false;
        output.Text = "Connecting to " + host + "...\r\n";

        try
        {
            var result = await Task.Run(() =>
            {
                using var client = new SshClient(host, port, username, password);
                client.ConnectionInfo.Timeout = TimeSpan.FromSeconds(12);
                client.KeepAliveInterval = TimeSpan.FromSeconds(10);
                client.Connect();

                string version = RunSshCommand(client, "show version");
                string detected = DetectSwitchVendor(requestedVendor, version);
                string[] commands = VlanCommands(detected);

                string vlanOutput = "";
                string usedCommand = "";
                foreach (var cmd in commands)
                {
                    var candidate = RunSshCommand(client, cmd);
                    if (LooksLikeUsefulVlanOutput(candidate))
                    {
                        vlanOutput = candidate;
                        usedCommand = cmd;
                        break;
                    }
                }

                client.Disconnect();

                if (string.IsNullOrWhiteSpace(vlanOutput))
                    throw new InvalidOperationException("Connected successfully, but no supported VLAN command returned usable output.");

                var sb = new StringBuilder();
                sb.AppendLine("PINGS Switch VLAN Discovery");
                sb.AppendLine("Switch: " + host);
                sb.AppendLine("Detected/selected platform: " + detected);
                sb.AppendLine("Command: " + usedCommand);
                sb.AppendLine(new string('-', 72));
                sb.AppendLine(vlanOutput.TrimEnd());
                return sb.ToString();
            });

            output.Text = result;
            tabs.SelectedTab = output.Parent as TabPage;
        }
        catch (Exception ex)
        {
            output.Text += "\r\nERROR: " + ex.Message +
                "\r\n\r\nThe account must be allowed to SSH to the switch and run read-only show commands.";
        }
        finally
        {
            button.Enabled = true;
        }
    }

    private static string RunSshCommand(SshClient client, string command)
    {
        using var cmd = client.CreateCommand(command);
        cmd.CommandTimeout = TimeSpan.FromSeconds(15);
        string result = cmd.Execute() ?? "";
        if (!string.IsNullOrWhiteSpace(cmd.Error))
            result += Environment.NewLine + cmd.Error;
        return result;
    }

    private static string DetectSwitchVendor(string requested, string version)
    {
        if (!string.Equals(requested, "Auto", StringComparison.OrdinalIgnoreCase))
            return requested;

        string v = version.ToLowerInvariant();
        if (v.Contains("arubaos-cx") || v.Contains("aos-cx")) return "Aruba CX";
        if (v.Contains("procurve") || v.Contains("arubaos-switch") || v.Contains("hewlett packard enterprise") || v.Contains("hp j"))
            return "ArubaOS-Switch / ProCurve";
        if (v.Contains("nortel") || v.Contains("avaya") || v.Contains("ethernet routing switch"))
            return "Nortel / Avaya ERS";
        if (v.Contains("cisco") || v.Contains("ios xe") || v.Contains("ios-xe"))
            return "Cisco IOS / IOS-XE";
        return "Auto";
    }

    private static string[] VlanCommands(string vendor) => vendor switch
    {
        "Cisco IOS / IOS-XE" => new[] { "show vlan brief", "show vlan" },
        "Aruba CX" => new[] { "show vlan" },
        "ArubaOS-Switch / ProCurve" => new[] { "show vlans", "show vlan" },
        "Nortel / Avaya ERS" => new[] { "show vlan", "show vlan basic" },
        _ => new[] { "show vlan brief", "show vlan", "show vlans", "show vlan basic" }
    };

    private static bool LooksLikeUsefulVlanOutput(string text)
    {
        if (string.IsNullOrWhiteSpace(text)) return false;
        string t = text.ToLowerInvariant();
        if (t.Contains("invalid input") || t.Contains("unknown command") ||
            t.Contains("unrecognized command") || t.Contains("incomplete command") ||
            t.Contains("ambiguous command") || t.Contains("command not found"))
            return false;

        return t.Contains("vlan") &&
               (t.Contains("name") || t.Contains("status") || t.Contains("port") || t.Contains("vid"));
    }

    private static void StyleButton(Button b)
    {
        b.FlatStyle = FlatStyle.Standard;
        b.UseVisualStyleBackColor = true;
        b.Font = new Font("Segoe UI", 9.25f);
        b.Padding = new Padding(8, 3, 8, 3);
        b.Margin = new Padding(4, 3, 4, 3);
        b.MinimumSize = new Size(86, 31);
        b.Cursor = Cursors.Hand;
    }

    private DataGridView NewGrid()
    {
        var grid = new DataGridView
        {
            Dock = DockStyle.Fill,
            ReadOnly = true,
            AllowUserToAddRows = false,
            AllowUserToDeleteRows = false,
            AutoGenerateColumns = true,
            AutoSizeColumnsMode = DataGridViewAutoSizeColumnsMode.AllCells,
            SelectionMode = DataGridViewSelectionMode.FullRowSelect,
            MultiSelect = true,
            BackgroundColor = Color.White,
            BorderStyle = BorderStyle.None,
            GridColor = Color.FromArgb(225, 230, 236),
            RowHeadersVisible = false,
            EnableHeadersVisualStyles = false,
            ColumnHeadersHeight = 34,
            RowTemplate = { Height = 30 }
        };
        grid.ColumnHeadersDefaultCellStyle.BackColor = Color.FromArgb(45, 63, 82);
        grid.ColumnHeadersDefaultCellStyle.ForeColor = Color.White;
        grid.ColumnHeadersDefaultCellStyle.Font = new Font("Segoe UI Semibold", 9f);
        grid.DefaultCellStyle.SelectionBackColor = Color.FromArgb(205, 225, 246);
        grid.DefaultCellStyle.SelectionForeColor = Color.Black;
        grid.DataBindingComplete += (_, _) => ApplyRowColors(grid);
        AttachClassificationMenu(grid);
        return grid;
    }

    private static void ApplyRowColors(DataGridView grid)
    {
        foreach (DataGridViewRow row in grid.Rows)
        {
            if (row.DataBoundItem is not DeviceRow d) continue;
            if (d.Status == "Pingable")
            {
                row.DefaultCellStyle.BackColor = Color.LightGreen;
                row.DefaultCellStyle.ForeColor = Color.Black;
            }
            else
            {
                row.DefaultCellStyle.BackColor = Color.MistyRose;
                row.DefaultCellStyle.ForeColor = Color.DarkRed;
            }
            if (!string.IsNullOrWhiteSpace(d.Change))
                row.DefaultCellStyle.Font = new Font(grid.Font, FontStyle.Bold);
        }
    }

    private static DeviceRow Clone(DeviceRow d) => new()
    {
        IP = d.IP, Hostname = d.Hostname, MAC = d.MAC, Vendor = d.Vendor,
        Type = d.Type, Group = d.Group, Status = d.Status, LatencyMs = d.LatencyMs, Change = d.Change
    };

    private void AttachClassificationMenu(DataGridView grid)
    {
        var menu = new ContextMenuStrip();
        menu.Items.Add("Mark as HP Aruba Access Point", null, (_, _) =>
            ApplyManualClassification(grid, "HP Aruba", "Access Point"));
        menu.Items.Add("Mark as Cisco Access Point", null, (_, _) =>
            ApplyManualClassification(grid, "Cisco / Meraki", "Access Point"));
        menu.Items.Add("Mark as PC / Desktop", null, (_, _) =>
            ApplyManualClassification(grid, "Manual", "PC / Desktop"));
        menu.Items.Add("Mark as Non-AP / Other Device", null, (_, _) =>
            ApplyManualClassification(grid, "Manual", "Other Device"));
        menu.Items.Add("Mark as Unknown", null, (_, _) =>
            ApplyManualClassification(grid, "", "Unknown"));
        menu.Items.Add(new ToolStripSeparator());
        menu.Items.Add("Assign Selected to Custom Group...", null, (_, _) =>
            AssignCustomGroup(grid));
        menu.Items.Add("Clear Custom Group", null, (_, _) =>
            ClearCustomGroup(grid));
        menu.Items.Add(new ToolStripSeparator());
        menu.Items.Add("Clear Manual Classification", null, (_, _) =>
            ClearManualClassification(grid));

        grid.ContextMenuStrip = menu;
        grid.CellMouseDown += (_, e) =>
        {
            if (e.Button != MouseButtons.Right || e.RowIndex < 0) return;
            if (!grid.Rows[e.RowIndex].Selected)
            {
                grid.ClearSelection();
                grid.Rows[e.RowIndex].Selected = true;
            }
            if (grid.Rows[e.RowIndex].Cells.Count > 0)
                grid.CurrentCell = grid.Rows[e.RowIndex].Cells[0];
        };
    }

    private void ApplyManualClassification(DataGridView grid, string vendor, string type)
    {
        var selected = grid.SelectedRows.Cast<DataGridViewRow>()
            .Select(r => r.DataBoundItem as DeviceRow)
            .Where(d => d != null)
            .Cast<DeviceRow>()
            .ToList();

        if (selected.Count == 0 && grid.CurrentRow?.DataBoundItem is DeviceRow one)
            selected.Add(one);

        foreach (var d in selected)
        {
            var live = current.FirstOrDefault(x => x.IP == d.IP);
            if (live == null) continue;

            live.Vendor = vendor;
            live.Type = type;
            SaveOverride(live, new ManualOverride { Vendor = vendor, Type = type, Group = live.Group });
        }

        RefreshViews();
    }

    private void ClearManualClassification(DataGridView grid)
    {
        var selected = grid.SelectedRows.Cast<DataGridViewRow>()
            .Select(r => r.DataBoundItem as DeviceRow)
            .Where(d => d != null)
            .Cast<DeviceRow>()
            .ToList();

        if (selected.Count == 0 && grid.CurrentRow?.DataBoundItem is DeviceRow one)
            selected.Add(one);

        foreach (var d in selected)
        {
            var live = current.FirstOrDefault(x => x.IP == d.IP);
            if (live == null) continue;

            foreach (var key in CandidateKeys(live.IP, live.Hostname, live.MAC))
                manualOverrides.Remove(key);

            live.Vendor = VendorFromMac(live.MAC);
            live.Type = IsLocalComputerIp(live.IP) ? "PC / Desktop" : Classify(live.Hostname, live.Vendor);
            live.Group = "";
        }

        PersistOverrides();
        RefreshViews();
    }

    private void AssignCustomGroup(DataGridView grid)
    {
        var selected = GetSelectedDevices(grid);
        if (selected.Count == 0) return;

        var group = PromptForText("Custom Group", "Enter a group name for the selected devices:");
        if (string.IsNullOrWhiteSpace(group)) return;
        group = group.Trim();

        foreach (var d in selected)
        {
            var live = current.FirstOrDefault(x => x.IP == d.IP);
            if (live == null) continue;
            live.Group = group;
            SaveOverride(live, new ManualOverride { Vendor = live.Vendor, Type = live.Type, Group = group });
        }
        RefreshViews();
    }

    private void ClearCustomGroup(DataGridView grid)
    {
        foreach (var d in GetSelectedDevices(grid))
        {
            var live = current.FirstOrDefault(x => x.IP == d.IP);
            if (live == null) continue;
            live.Group = "";
            SaveOverride(live, new ManualOverride { Vendor = live.Vendor, Type = live.Type, Group = "" });
        }
        RefreshViews();
    }

    private static List<DeviceRow> GetSelectedDevices(DataGridView grid)
    {
        var selected = grid.SelectedRows.Cast<DataGridViewRow>()
            .Select(r => r.DataBoundItem as DeviceRow)
            .Where(d => d != null)
            .Cast<DeviceRow>()
            .ToList();

        if (selected.Count == 0 && grid.CurrentRow?.DataBoundItem is DeviceRow one)
            selected.Add(one);
        return selected;
    }

    private static string? PromptForText(string title, string prompt)
    {
        using var form = new Form
        {
            Text = title,
            Width = 440,
            Height = 165,
            StartPosition = FormStartPosition.CenterParent,
            FormBorderStyle = FormBorderStyle.FixedDialog,
            MinimizeBox = false,
            MaximizeBox = false
        };
        var label = new Label { Left = 14, Top = 14, Width = 390, Text = prompt };
        var box = new TextBox { Left = 14, Top = 42, Width = 390 };
        var ok = new Button { Text = "OK", Left = 248, Width = 75, Top = 78, DialogResult = DialogResult.OK };
        var cancel = new Button { Text = "Cancel", Left = 329, Width = 75, Top = 78, DialogResult = DialogResult.Cancel };
        form.Controls.AddRange(new Control[] { label, box, ok, cancel });
        form.AcceptButton = ok;
        form.CancelButton = cancel;
        return form.ShowDialog() == DialogResult.OK ? box.Text : null;
    }

    private static IEnumerable<string> CandidateKeys(string ip, string host, string mac)
    {
        if (!string.IsNullOrWhiteSpace(mac))
            yield return "mac:" + mac.Replace(":", "").Replace("-", "").ToUpperInvariant();
        if (!string.IsNullOrWhiteSpace(host))
            yield return "host:" + host.Trim().ToLowerInvariant();
        if (!string.IsNullOrWhiteSpace(ip))
            yield return "ip:" + ip.Trim();
    }

    private ManualOverride? GetOverride(string ip, string host, string mac)
    {
        foreach (var key in CandidateKeys(ip, host, mac))
            if (manualOverrides.TryGetValue(key, out var value))
                return value;
        return null;
    }

    private void SaveOverride(DeviceRow d, ManualOverride value)
    {
        var key = CandidateKeys(d.IP, d.Hostname, d.MAC).FirstOrDefault();
        if (key == null) return;
        manualOverrides[key] = value;
        PersistOverrides();
    }

    private void LoadOverrides()
    {
        try
        {
            if (!File.Exists(overrideFile)) return;
            var json = File.ReadAllText(overrideFile);
            var loaded = JsonSerializer.Deserialize<Dictionary<string, ManualOverride>>(json);
            if (loaded == null) return;
            foreach (var kv in loaded) manualOverrides[kv.Key] = kv.Value;
        }
        catch { }
    }

    private void PersistOverrides()
    {
        try
        {
            Directory.CreateDirectory(Path.GetDirectoryName(overrideFile)!);
            File.WriteAllText(overrideFile,
                JsonSerializer.Serialize(manualOverrides, new JsonSerializerOptions { WriteIndented = true }));
        }
        catch { }
    }

    private string CurrentSubnet()
    {
        foreach (var ni in NetworkInterface.GetAllNetworkInterfaces().Where(n => n.OperationalStatus == OperationalStatus.Up))
        {
            foreach (var ua in ni.GetIPProperties().UnicastAddresses.Where(a => a.Address.AddressFamily == AddressFamily.InterNetwork))
            {
                if (IPAddress.IsLoopback(ua.Address) || ua.IPv4Mask == null) continue;
                var a = ua.Address.GetAddressBytes();
                var m = ua.IPv4Mask.GetAddressBytes();
                var n = a.Zip(m, (x, y) => (byte)(x & y)).ToArray();
                int bits = m.Sum(x => Convert.ToString(x, 2).Count(c => c == '1'));
                return new IPAddress(n) + "/" + bits;
            }
        }
        return "192.168.1.0/24";
    }

    private async Task ScanAsync()
    {
        var parts = subnet.Text.Trim().Split('/');
        if (parts.Length != 2 || !IPAddress.TryParse(parts[0], out var baseIp) ||
            !int.TryParse(parts[1], out int prefix) || prefix < 16 || prefix > 30)
        {
            MessageBox.Show("Use IPv4 CIDR, for example 10.1.2.0/24");
            return;
        }

        UseWaitCursor = true;
        summary.Text = "Scanning...";

        var bytes = baseIp.GetAddressBytes();
        uint raw = ((uint)bytes[0] << 24) | ((uint)bytes[1] << 16) | ((uint)bytes[2] << 8) | bytes[3];
        uint mask = uint.MaxValue << (32 - prefix);
        uint network = raw & mask;
        int hostCount = (int)Math.Min((1L << (32 - prefix)) - 2, 4094);

        var bag = new ConcurrentBag<DeviceRow>();

        await Parallel.ForEachAsync(
            Enumerable.Range(1, hostCount),
            new ParallelOptions { MaxDegreeOfParallelism = 64 },
            async (i, _) =>
            {
                uint v = network + (uint)i;
                string ip = $"{v >> 24}.{(v >> 16) & 255}.{(v >> 8) & 255}.{v & 255}";
                bool up = false;
                long ms = -1;

                try
                {
                    using var p = new Ping();
                    var r = await p.SendPingAsync(ip, 700);
                    up = r.Status == IPStatus.Success;
                    if (up) ms = r.RoundtripTime;
                }
                catch { }

                string host = "";
                string mac = "";
                string vendor = "";
                if (up)
                {
                    try { host = (await Dns.GetHostEntryAsync(ip)).HostName; } catch { }
                    mac = ResolveMac(ip);
                    vendor = VendorFromMac(mac);
                }

                string type = IsLocalComputerIp(ip) ? "PC / Desktop" : Classify(host, vendor);
                var manual = GetOverride(ip, host, mac);
                if (manual != null)
                {
                    vendor = manual.Vendor;
                    type = manual.Type;
                }

                string group = manual?.Group ?? "";

                bag.Add(new DeviceRow
                {
                    IP = ip,
                    Hostname = host,
                    MAC = mac,
                    Vendor = vendor,
                    Type = type,
                    Group = group,
                    Status = up ? "Pingable" : "No Ping",
                    LatencyMs = ms
                });
            });

        current = bag.OrderBy(d => IpToUInt(d.IP)).ToList();
        compareMode = false;
        UseWaitCursor = false;
        RefreshViews();
    }

    private static uint IpToUInt(string ip)
    {
        var b = IPAddress.Parse(ip).GetAddressBytes();
        return ((uint)b[0] << 24) | ((uint)b[1] << 16) | ((uint)b[2] << 8) | b[3];
    }

    [DllImport("iphlpapi.dll", ExactSpelling = true)]
    private static extern int SendARP(int DestIP, int SrcIP, byte[] pMacAddr, ref int PhyAddrLen);

    private static string ResolveMac(string ip)
    {
        try
        {
            var dest = BitConverter.ToInt32(IPAddress.Parse(ip).GetAddressBytes(), 0);
            var mac = new byte[6];
            int len = mac.Length;
            if (SendARP(dest, 0, mac, ref len) != 0 || len <= 0) return "";
            return string.Join(":", mac.Take(len).Select(b => b.ToString("X2")));
        }
        catch { return ""; }
    }

    private static string VendorFromMac(string mac)
    {
        string p = mac.Replace(":", "").Replace("-", "").ToUpperInvariant();
        if (p.Length < 6) return "";

        // Verified Aruba AP/vendor prefixes from the supplied list.
        // Entries that resolve to Brocade, Huawei, Sitecom, or Cisco were removed.
        string[] aruba =
        {
            "000B86","001A1E","00246C","204C03","24DEC6","40E3D6","482F6B",
            "6026EF","703A0E","84D47E","94B40F","988F00","ACA31E","B01F8C",
            "B45D50","D8C7C8","E81098","F05C19","F42E7F"
        };

        // HPE-owned prefix documented by Aruba on AP-344/AP-345 examples.
        string[] hpeAruba = { "C8B5AD" };

        // Cisco Systems prefixes identify the vendor, but NOT the device role.
        string[] cisco =
        {
            "00077D","00141B","001AA1","00270D","2C3124","380E4D","40A6E8",
            "70695A","A0ECF9","F44E05","A44C11"
        };

        // Verified Cisco Meraki vendor prefixes from the supplied list.
        // Meraki also makes switches/security/cameras, so vendor alone does not mean AP.
        string[] meraki =
        {
            "00180A","08F1B3","0C7BC8","149F43","E0553D","AC17C8"
        };

        if (aruba.Any(p.StartsWith)) return "HP Aruba";
        if (hpeAruba.Any(p.StartsWith)) return "HPE / Aruba";
        if (meraki.Any(p.StartsWith)) return "Cisco Meraki";
        if (cisco.Any(p.StartsWith)) return "Cisco Systems";
        if (p.StartsWith("001BED")) return "Brocade";
        if (p.StartsWith("643E8C") || p.StartsWith("00259E")) return "Huawei";
        if (p.StartsWith("64D1A3")) return "Sitecom";
        return "";
    }

    private static string Classify(string host, string vendor)
    {
        string h = host.ToLowerInvariant();
        string v = vendor.ToLowerInvariant();

        // Aruba/HPE prefixes in the verified AP list are treated as AP candidates.
        // Cisco/Meraki OUIs identify vendor only; require an AP-specific hostname or manual marking.
        if (v.Contains("aruba") ||
            h.Contains("aironet") || h.Contains("meraki") || h.Contains("wireless-ap") ||
            h.Contains("-ap") || h.StartsWith("ap-") || h.StartsWith("ap") || h.StartsWith("wap"))
            return "Access Point";

        if (h.Contains("desktop") || h.Contains("laptop") || h.Contains("workstation") ||
            h.StartsWith("pc-") || h.StartsWith("win-") || h.StartsWith("ws-") ||
            h.StartsWith("lt-") || h.StartsWith("nb-") || h.StartsWith("dt-") ||
            h.Contains("windows"))
            return "PC / Desktop";

        if (h.Contains("printer") || h.Contains("xerox") || h.Contains("canon") || h.Contains("brother") ||
            h.Contains("camera") || h.Contains("phone") || h.Contains("iphone") || h.Contains("android") ||
            h.Contains("switch") || h.Contains("router") || h.Contains("gateway") || h.Contains("lantronix"))
            return "Other Device";

        return "Unknown";
    }

    private static bool IsLocalComputerIp(string ip)
    {
        try
        {
            return NetworkInterface.GetAllNetworkInterfaces()
                .Where(n => n.OperationalStatus == OperationalStatus.Up)
                .SelectMany(n => n.GetIPProperties().UnicastAddresses)
                .Where(a => a.Address.AddressFamily == AddressFamily.InterNetwork)
                .Any(a => a.Address.ToString() == ip);
        }
        catch { return false; }
    }

    private List<DeviceRow> BuildDifferenceRows()
    {
        var diffs = new List<DeviceRow>();
        var oldByIp = baseline.ToDictionary(x => x.IP);

        foreach (var row in current)
        {
            if (row.Status != "Pingable") continue;

            if (!oldByIp.TryGetValue(row.IP, out var old) || old.Status != "Pingable")
            {
                var d = Clone(row);
                d.Change = "NEW";
                diffs.Add(d);
                continue;
            }

            if (!string.Equals(old.Hostname, row.Hostname, StringComparison.OrdinalIgnoreCase) ||
                !string.Equals(old.MAC, row.MAC, StringComparison.OrdinalIgnoreCase) ||
                !string.Equals(old.Type, row.Type, StringComparison.OrdinalIgnoreCase) ||
                !string.Equals(old.Group, row.Group, StringComparison.OrdinalIgnoreCase))
            {
                var d = Clone(row);
                d.Change = "CHANGED";
                diffs.Add(d);
            }
        }

        foreach (var old in baseline.Where(x => x.Status == "Pingable"))
        {
            var now = current.FirstOrDefault(n => n.IP == old.IP);
            if (now == null || now.Status != "Pingable")
            {
                var d = Clone(old);
                d.Status = "Missing";
                d.Change = "MISSING";
                diffs.Add(d);
            }
        }

        return diffs.OrderBy(x => IpToUInt(x.IP)).ToList();
    }

    private void ExportResults()
    {
        if (baseline.Count == 0)
        {
            MessageBox.Show("Set a Before baseline first.");
            return;
        }
        if (current.Count == 0)
        {
            MessageBox.Show("Run an After scan first.");
            return;
        }

        using var dialog = new SaveFileDialog
        {
            Title = "Export PINGS Before / After / Difference",
            Filter = "CSV file (*.csv)|*.csv|Text file (*.txt)|*.txt",
            DefaultExt = "csv",
            AddExtension = true,
            FileName = "PINGS_compare_" + DateTime.Now.ToString("yyyyMMdd_HHmmss") + ".csv"
        };

        if (dialog.ShowDialog() != DialogResult.OK) return;

        var ext = Path.GetExtension(dialog.FileName).ToLowerInvariant();
        var text = ext == ".txt" ? BuildTextExport() : BuildCsvExport();
        File.WriteAllText(dialog.FileName, text, new UTF8Encoding(true));
        MessageBox.Show("Export saved:\n" + dialog.FileName);
    }

    private string BuildCsvExport()
    {
        var sb = new StringBuilder();
        sb.AppendLine("Section,IP,Hostname,MAC,Vendor,Type,Group,Status,LatencyMs,Change");

        void AddRows(string section, IEnumerable<DeviceRow> rows)
        {
            foreach (var d in rows)
            {
                sb.AppendLine(string.Join(",", new[]
                {
                    Csv(section), Csv(d.IP), Csv(d.Hostname), Csv(d.MAC), Csv(d.Vendor),
                    Csv(d.Type), Csv(d.Group), Csv(d.Status),
                    Csv(d.LatencyMs >= 0 ? d.LatencyMs.ToString() : ""), Csv(d.Change)
                }));
            }
        }

        AddRows("BEFORE", baseline);
        AddRows("AFTER", current);
        AddRows("DIFFERENCE", BuildDifferenceRows());
        return sb.ToString();
    }

    private string BuildTextExport()
    {
        var sb = new StringBuilder();
        sb.AppendLine("PINGS Before / After / Difference Export");
        sb.AppendLine("Generated: " + DateTime.Now);
        sb.AppendLine("Subnet: " + subnet.Text);
        sb.AppendLine();
        sb.AppendLine($"BEFORE COUNTS: Pingable {CountPingable(baseline)}, APs {CountAPs(baseline)}, Non-APs {CountNonAPs(baseline)}, Not Responding {CountNoPing(baseline)}");
        sb.AppendLine($"AFTER COUNTS:  Pingable {CountPingable(current)}, APs {CountAPs(current)}, Non-APs {CountNonAPs(current)}, Not Responding {CountNoPing(current)}");
        sb.AppendLine();

        void AddRows(string title, IEnumerable<DeviceRow> rows)
        {
            sb.AppendLine("===== " + title + " =====");
            sb.AppendLine("IP\tHostname\tMAC\tVendor\tType\tGroup\tStatus\tLatencyMs\tChange");
            foreach (var d in rows)
            {
                sb.AppendLine(string.Join("\t", new[]
                {
                    d.IP, d.Hostname, d.MAC, d.Vendor, d.Type, d.Group, d.Status,
                    d.LatencyMs >= 0 ? d.LatencyMs.ToString() : "", d.Change
                }));
            }
            sb.AppendLine();
        }

        AddRows("BEFORE", baseline);
        AddRows("AFTER", current);
        AddRows("DIFFERENCE", BuildDifferenceRows());
        return sb.ToString();
    }

    private static string Csv(string value)
    {
        value ??= "";
        return "\"" + value.Replace("\"", "\"\"") + "\"";
    }

    private List<DeviceRow> BuildRows()
    {
        var rows = current.Select(Clone).ToList();

        if (compareMode)
        {
            var oldByIp = baseline.ToDictionary(x => x.IP);
            foreach (var row in rows)
            {
                oldByIp.TryGetValue(row.IP, out var old);

                if (row.Status == "Pingable" && (old == null || old.Status != "Pingable"))
                {
                    row.Change = "NEW";
                }
                else if (old != null && row.Status == "Pingable" &&
                         (!string.Equals(old.Hostname, row.Hostname, StringComparison.OrdinalIgnoreCase) ||
                          !string.Equals(old.MAC, row.MAC, StringComparison.OrdinalIgnoreCase) ||
                          !string.Equals(old.Type, row.Type, StringComparison.OrdinalIgnoreCase) ||
                          !string.Equals(old.Group, row.Group, StringComparison.OrdinalIgnoreCase)))
                {
                    row.Change = "CHANGED";
                }
            }

            foreach (var old in baseline.Where(x =>
                         x.Status == "Pingable" &&
                         !current.Any(n => n.IP == x.IP && n.Status == "Pingable")))
            {
                var missing = Clone(old);
                missing.Status = "Missing";
                missing.Change = "MISSING";
                rows.Add(missing);
            }
        }

        return rows;
    }

    private IEnumerable<DeviceRow> StatusFilter(IEnumerable<DeviceRow> rows)
    {
        return filter.Text switch
        {
            "Pingable" => rows.Where(x => x.Status == "Pingable"),
            "No Ping" => rows.Where(x => x.Status == "No Ping" || x.Status == "Missing"),
            _ => rows
        };
    }

    private void RefreshViews()
    {
        var all = BuildRows();
        grids["All Pings"].DataSource = StatusFilter(all).ToList();
        grids["Access Points"].DataSource = StatusFilter(all.Where(x => x.Type == "Access Point")).ToList();
        grids["Non-Access-Points"].DataSource = StatusFilter(all.Where(x => x.Status == "Pingable" && x.Type != "Access Point")).ToList();
        grids["PCs / Desktops"].DataSource = StatusFilter(all.Where(x => x.Type == "PC / Desktop")).ToList();
        grids["Other Devices"].DataSource = StatusFilter(all.Where(x => x.Type == "Other Device")).ToList();
        grids["Unknown"].DataSource = StatusFilter(all.Where(x => x.Type == "Unknown")).ToList();
        grids["Custom Groups"].DataSource = StatusFilter(all.Where(x => !string.IsNullOrWhiteSpace(x.Group))).ToList();
        grids["Missing / Changed"].DataSource = StatusFilter(all.Where(x => !string.IsNullOrEmpty(x.Change))).ToList();

        UpdateCounts();

        summary.Text =
            $"Current: Pingable {CountPingable(current)} | APs {CountAPs(current)} | " +
            $"Non-APs {CountNonAPs(current)} | Not Responding {CountNoPing(current)}";

        compareSummary.Text = baseline.Count == 0 ? "" :
            $"Before→Now: Pingable {CountPingable(baseline)}→{CountPingable(current)} | " +
            $"APs {CountAPs(baseline)}→{CountAPs(current)} | " +
            $"Non-APs {CountNonAPs(baseline)}→{CountNonAPs(current)} | " +
            $"Not Responding {CountNoPing(baseline)}→{CountNoPing(current)}";
    }

    private static string Delta(int value) => value > 0 ? "+" + value : value.ToString();

    private static int CountPingable(IEnumerable<DeviceRow> rows) => rows.Count(x => x.Status == "Pingable");
    private static int CountAPs(IEnumerable<DeviceRow> rows) => rows.Count(x => x.Status == "Pingable" && x.Type == "Access Point");
    private static int CountNonAPs(IEnumerable<DeviceRow> rows) => rows.Count(x => x.Status == "Pingable" && x.Type != "Access Point");
    private static int CountNoPing(IEnumerable<DeviceRow> rows) => rows.Count(x => x.Status == "No Ping" || x.Status == "Missing");

    private void UpdateCounts()
    {
        pingableBtn.Text = $"Pingable: {CountPingable(current)}";
        apBtn.Text = $"APs: {CountAPs(current)}";
        nonApBtn.Text = $"Non-APs: {CountNonAPs(current)}";
        noPingBtn.Text = $"Not Responding: {CountNoPing(current)}";
    }
}

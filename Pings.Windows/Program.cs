using System.Collections.Concurrent;
using System.Net;
using System.Net.NetworkInformation;
using System.Net.Sockets;
using System.Runtime.InteropServices;

ApplicationConfiguration.Initialize();
Application.Run(new MainForm());

public sealed class DeviceRow
{
    public string IP { get; set; } = "";
    public string Hostname { get; set; } = "";
    public string MAC { get; set; } = "";
    public string Vendor { get; set; } = "";
    public string Type { get; set; } = "Unknown";
    public string Status { get; set; } = "";
    public long LatencyMs { get; set; } = -1;
    public string Change { get; set; } = "";
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

    public MainForm()
    {
        Text = "PINGS v0.6";
        Width = 1320;
        Height = 780;

        filter.Items.AddRange(new object[] { "All", "Pingable", "No Ping" });
        filter.SelectedIndex = 0;
        repeat.Items.AddRange(new object[] { "Off", "30 sec", "5 min" });
        repeat.SelectedIndex = 0;

        var top = new FlowLayoutPanel
        {
            Dock = DockStyle.Top,
            Height = 86,
            WrapContents = true,
            AutoScroll = true
        };

        var currentSubnet = new Button { Text = "Current Subnet", AutoSize = true };
        var scan = new Button { Text = "Scan Now", AutoSize = true };
        var setBefore = new Button { Text = "Set Before", AutoSize = true };
        var compare = new Button { Text = "Compare Before/After", AutoSize = true };

        top.Controls.AddRange(new Control[]
        {
            new Label { Text = "Subnet:", AutoSize = true, Padding = new Padding(0,8,0,0) },
            subnet, currentSubnet, scan,
            new Label { Text = "Show:", AutoSize = true, Padding = new Padding(8,8,0,0) },
            filter,
            new Label { Text = "Repeat:", AutoSize = true, Padding = new Padding(8,8,0,0) },
            repeat,
            setBefore, compare,
            pingableBtn, apBtn, nonApBtn, noPingBtn,
            summary, compareSummary
        });

        foreach (var name in new[]
        {
            "All Pings", "Access Points", "Non-Access-Points",
            "PCs / Desktops", "Other Devices", "Unknown", "Missing / Changed"
        })
        {
            var page = new TabPage(name);
            var grid = NewGrid();
            grids[name] = grid;
            page.Controls.Add(grid);
            tabs.TabPages.Add(page);
        }

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
            RefreshViews();
            tabs.SelectedIndex = 6;
        };
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
            MultiSelect = false
        };
        grid.DataBindingComplete += (_, _) => ApplyRowColors(grid);
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
        Type = d.Type, Status = d.Status, LatencyMs = d.LatencyMs, Change = d.Change
    };

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

                bag.Add(new DeviceRow
                {
                    IP = ip,
                    Hostname = host,
                    MAC = mac,
                    Vendor = vendor,
                    Type = Classify(host, vendor),
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

        string[] aruba = { "000B86","001A1E","00246C","186472","24DEC6","40E3D6","6CF37F","84D47E","94B40F","B45D50" };
        string[] cisco = { "00000C","000142","000143","000AB7","000BFC","000C30","000D28","000D65","000E38","000E83","000F23","001007","00100B","001011","001054","00105A","00107B","0010A6","0010F6","001120","001121","00115C","001192","0011BB" };

        if (aruba.Any(p.StartsWith)) return "Aruba";
        if (cisco.Any(p.StartsWith)) return "Cisco";
        return "";
    }

    private static string Classify(string host, string vendor)
    {
        string h = host.ToLowerInvariant();
        string v = vendor.ToLowerInvariant();

        if (v.Contains("aruba") || v.Contains("cisco") ||
            h.Contains("aruba") || h.Contains("cisco") || h.StartsWith("ap-") || h.StartsWith("ap"))
            return "Access Point";

        if (h.Contains("desktop") || h.Contains("laptop") || h.Contains("workstation") || h.Contains("pc-"))
            return "PC / Desktop";

        if (h.Contains("printer") || h.Contains("xerox") || h.Contains("canon") || h.Contains("brother") ||
            h.Contains("camera") || h.Contains("phone") || h.Contains("iphone") || h.Contains("android") ||
            h.Contains("switch") || h.Contains("router") || h.Contains("gateway") || h.Contains("lantronix"))
            return "Other Device";

        return "Unknown";
    }

    private List<DeviceRow> BuildRows()
    {
        var rows = current.Select(Clone).ToList();

        if (compareMode)
        {
            var oldByIp = baseline.ToDictionary(x => x.IP);
            foreach (var row in rows)
            {
                if (row.Status == "Pingable" && !oldByIp.ContainsKey(row.IP))
                {
                    row.Change = "NEW";
                }
                else if (oldByIp.TryGetValue(row.IP, out var old) && row.Status == "Pingable" &&
                         (!string.Equals(old.Hostname, row.Hostname, StringComparison.OrdinalIgnoreCase) ||
                          !string.Equals(old.MAC, row.MAC, StringComparison.OrdinalIgnoreCase)))
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

using System;
using System.Diagnostics;

// 本机 Command Processor AutoRun 值异常；仅对子进程禁用 AutoRun，不修改注册表。
class CleanCmd {
    static int Main() {
        string raw = Environment.CommandLine;
        int end = raw.StartsWith("\"") ? raw.IndexOf('"', 1) + 1 : raw.IndexOf(' ');
        string arguments = end < 0 ? "" : raw.Substring(end);
        var start = new ProcessStartInfo(Environment.GetFolderPath(Environment.SpecialFolder.System) + "\\cmd.exe", "/d " + arguments);
        start.UseShellExecute = false;
        using (var process = Process.Start(start)) {
            process.WaitForExit();
            return process.ExitCode;
        }
    }
}

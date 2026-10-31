// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.terminal

/**
 * Bundled seed list (SPEC.md §7). Weight is a rough "how often does this appear at the start of a
 * command line" prior; history, once available, ranks above it.
 */
object StaticCommands {
    class Entry(val text: String, val weight: Float)

    private fun e(text: String, weight: Float) = Entry(text, weight)

    val entries: List<Entry> = listOf(
        // navigation / files
        e("cd", 10f), e("cd ..", 9f), e("cd -", 7f), e("ls", 10f), e("ls -la", 9.5f),
        e("ls -lh", 8f), e("pwd", 9f), e("cat", 9f), e("less", 7.5f), e("head", 6.5f),
        e("tail", 7f), e("tail -f", 7.5f), e("mkdir -p", 7f), e("rm", 6.5f), e("rm -rf", 6f),
        e("cp", 6.5f), e("mv", 6.5f), e("touch", 6f), e("find", 7f), e("ln -s", 5f),
        e("chmod", 5.5f), e("chown", 5f), e("du -sh", 6f), e("df -h", 6.5f), e("tree", 5f),
        // text
        e("grep", 8f), e("grep -r", 7.5f), e("rg", 7f), e("sed", 6f), e("awk", 5.5f),
        e("sort", 5.5f), e("uniq", 5f), e("wc -l", 6f), e("cut", 5f), e("xargs", 5f),
        e("echo", 7f), e("nano", 5f), e("vim", 6f),
        // system
        e("ps aux", 7f), e("top", 6f), e("htop", 6.5f), e("kill", 5.5f), e("killall", 5f),
        e("systemctl status", 7f), e("systemctl start", 6f), e("systemctl stop", 6f),
        e("systemctl restart", 6.5f), e("journalctl -u", 7f), e("journalctl -f", 7f),
        e("sudo", 7.5f), e("whoami", 6f), e("uname -a", 5.5f), e("free -h", 6f),
        e("which", 5.5f), e("man", 5f), e("history", 6f), e("clear", 6.5f), e("exit", 7f),
        // network
        e("ssh", 8.5f), e("scp", 6.5f), e("rsync -avz", 7f), e("curl", 7f), e("wget", 6.5f),
        e("ping", 6f), e("ip a", 6f), e("ss -tulpn", 6f), e("tailscale status", 6.5f),
        e("tailscale ip", 6f), e("tailscale up", 6f),
        // git
        e("git", 10f), e("git status", 9.5f), e("git add", 8.5f), e("git add -A", 7.5f),
        e("git commit -m", 8.5f), e("git push", 8.5f), e("git pull", 8.5f),
        e("git log --oneline", 8f), e("git diff", 8f), e("git checkout", 7.5f),
        e("git branch", 7f), e("git stash", 6.5f), e("git clone", 6.5f), e("git rebase", 6f),
        e("git merge", 6f),
        // dev
        e("docker ps", 8f), e("docker logs -f", 7.5f), e("docker compose up -d", 8f),
        e("docker compose down", 7f), e("docker exec -it", 7f),
        e("npm install", 7f), e("npm run dev", 7f), e("npm test", 6.5f), e("pnpm", 6f),
        e("python3", 7f), e("pip install", 6.5f), e("node", 6.5f), e("make", 6f),
        e("tmux new -s", 6.5f), e("tmux attach -t", 7f), e("tmux ls", 6f),
        // termux
        e("pkg install", 7.5f), e("pkg update", 7.5f), e("pkg upgrade", 7f),
        e("termux-setup-storage", 6f), e("termux-clipboard-get", 5.5f), e("termux-open", 5f),
    )
}

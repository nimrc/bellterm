//! Importing nodes from an OpenSSH client configuration (`~/.ssh/config`).
//! Every concrete alias named on a `Host` line becomes one entry, with the
//! settings of every block that matches it, the first value winning as in
//! `ssh_config(5)`. `Match` blocks and `Include` cannot be evaluated off the
//! machine they belong to, so they are skipped and reported.

use serde::Serialize;

#[derive(Debug, Default, Clone, PartialEq, Eq, Serialize)]
pub struct Entry {
    pub alias: String,
    pub host_name: Option<String>,
    pub user: Option<String>,
    pub port: Option<u16>,
    /// `ProxyJump` hops in order, as written (`[user@]host[:port]` or an alias).
    pub proxy_jump: Vec<String>,
    pub identity_file: Option<String>,
    pub server_alive_interval: Option<u64>,
    /// `ProxyJump` was set, possibly to `none`; later blocks leave it alone.
    #[serde(skip)]
    jump_set: bool,
}

#[derive(Debug, Default, PartialEq, Eq, Serialize)]
pub struct Import {
    pub entries: Vec<Entry>,
    /// Directives that were not applied, by line, for the import summary.
    pub skipped: Vec<String>,
}

struct Block {
    patterns: Vec<String>,
    settings: Vec<(String, String)>,
}

pub fn parse(text: &str) -> Import {
    let mut blocks = vec![Block {
        patterns: vec!["*".into()],
        settings: Vec::new(),
    }];
    let mut skipped = Vec::new();
    let mut in_match = false;
    for (number, raw) in text.lines().enumerate() {
        let line = raw.trim();
        if line.is_empty() || line.starts_with('#') {
            continue;
        }
        let (keyword, value) = split(line);
        let keyword = keyword.to_ascii_lowercase();
        match keyword.as_str() {
            "host" => {
                in_match = false;
                blocks.push(Block {
                    patterns: value.split_whitespace().map(str::to_owned).collect(),
                    settings: Vec::new(),
                });
            }
            "match" => {
                in_match = true;
                skipped.push(format!("line {}: Match", number + 1));
            }
            "include" => skipped.push(format!("line {}: Include {value}", number + 1)),
            _ if in_match => {}
            _ => {
                if let Some(block) = blocks.last_mut() {
                    block.settings.push((keyword, unquote(value).to_owned()));
                }
            }
        }
    }
    let mut entries: Vec<Entry> = Vec::new();
    for alias in blocks
        .iter()
        .flat_map(|block| &block.patterns)
        .filter(|pattern| !pattern.contains(['*', '?', '!']))
    {
        if entries.iter().any(|entry| &entry.alias == alias) {
            continue;
        }
        let mut entry = Entry {
            alias: alias.clone(),
            ..Entry::default()
        };
        for block in blocks
            .iter()
            .filter(|block| matches(&block.patterns, alias))
        {
            for (keyword, value) in &block.settings {
                apply(&mut entry, keyword, value);
            }
        }
        entries.push(entry);
    }
    Import { entries, skipped }
}

/// `Keyword value`, `Keyword=value`, or `Keyword = value`.
fn split(line: &str) -> (&str, &str) {
    let end = line
        .find(|c: char| c.is_whitespace() || c == '=')
        .unwrap_or(line.len());
    let rest = line[end..].trim_start();
    let rest = rest.strip_prefix('=').unwrap_or(rest).trim();
    (&line[..end], rest)
}

fn unquote(value: &str) -> &str {
    value
        .strip_prefix('"')
        .and_then(|v| v.strip_suffix('"'))
        .unwrap_or(value)
}

/// First value wins, as `ssh` reads its configuration.
fn apply(entry: &mut Entry, keyword: &str, value: &str) {
    match keyword {
        "hostname" if entry.host_name.is_none() => entry.host_name = Some(value.to_owned()),
        "user" if entry.user.is_none() => entry.user = Some(value.to_owned()),
        "port" if entry.port.is_none() => entry.port = value.parse().ok(),
        "identityfile" if entry.identity_file.is_none() => {
            entry.identity_file = Some(value.to_owned())
        }
        "serveraliveinterval" if entry.server_alive_interval.is_none() => {
            entry.server_alive_interval = value.parse().ok();
        }
        "proxyjump" if !entry.jump_set => {
            entry.jump_set = true;
            if value.eq_ignore_ascii_case("none") {
                return;
            }
            entry.proxy_jump = value
                .split(',')
                .map(str::trim)
                .filter(|hop| !hop.is_empty())
                .map(|hop| hop.strip_prefix("ssh://").unwrap_or(hop).to_owned())
                .collect();
        }
        _ => {}
    }
}

/// Whether a `Host` line's patterns select `alias`: some pattern matches and
/// no negated one does.
fn matches(patterns: &[String], alias: &str) -> bool {
    let mut selected = false;
    for pattern in patterns {
        if let Some(negated) = pattern.strip_prefix('!') {
            if glob(negated, alias) {
                return false;
            }
        } else if glob(pattern, alias) {
            selected = true;
        }
    }
    selected
}

/// `*` and `?` wildcards, case-insensitively as `ssh` compares host names.
fn glob(pattern: &str, text: &str) -> bool {
    let pattern: Vec<char> = pattern.to_lowercase().chars().collect();
    let text: Vec<char> = text.to_lowercase().chars().collect();
    let (mut p, mut t, mut star, mut mark) = (0, 0, None, 0);
    while t < text.len() {
        if p < pattern.len() && (pattern[p] == '?' || pattern[p] == text[t]) {
            p += 1;
            t += 1;
        } else if p < pattern.len() && pattern[p] == '*' {
            star = Some(p);
            mark = t;
            p += 1;
        } else if let Some(s) = star {
            p = s + 1;
            mark += 1;
            t = mark;
        } else {
            return false;
        }
    }
    pattern[p..].iter().all(|c| *c == '*')
}

/// One `ProxyJump` hop as `(user, host, port)`.
pub fn jump(spec: &str) -> (Option<&str>, &str, Option<u16>) {
    let (user, rest) = match spec.rsplit_once('@') {
        Some((user, rest)) => (Some(user), rest),
        None => (None, spec),
    };
    if let Some(inner) = rest.strip_prefix('[')
        && let Some((host, port)) = inner.split_once(']')
    {
        return (
            user,
            host,
            port.strip_prefix(':').and_then(|p| p.parse().ok()),
        );
    }
    match rest.rsplit_once(':') {
        Some((host, port)) if !host.contains(':') => (user, host, port.parse().ok()),
        _ => (user, rest, None),
    }
}

#[cfg(test)]
#[allow(clippy::unwrap_used)]
mod tests;

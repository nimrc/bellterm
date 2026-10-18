//! The parts of a daemon snapshot the Android UI shows, as compact JSON:
//! workspaces with their worktree and branch, each workspace's tabs, panes,
//! and agents with the daemon's status, in the daemon's own order.

use herdr_client::protocol::{AgentStatus, ClientShellSnapshot};
use serde::Serialize;

#[derive(Debug, Serialize)]
pub struct ShellView<'a> {
    pub revision: u64,
    pub focused_workspace: Option<&'a str>,
    pub focused_tab: Option<&'a str>,
    pub focused_pane: Option<&'a str>,
    pub update_available: Option<&'a str>,
    pub workspaces: Vec<Workspace<'a>>,
    pub tabs: Vec<Tab<'a>>,
    pub panes: Vec<Pane<'a>>,
    pub agents: Vec<Agent<'a>>,
}

#[derive(Debug, Serialize)]
pub struct Workspace<'a> {
    pub id: &'a str,
    pub number: usize,
    pub label: &'a str,
    pub branch: Option<&'a str>,
    pub ahead: Option<usize>,
    pub behind: Option<usize>,
    pub worktree: Option<&'a str>,
    pub linked_worktree: bool,
    pub active_tab: &'a str,
    pub focused: bool,
    pub status: &'static str,
}

#[derive(Debug, Serialize)]
pub struct Tab<'a> {
    pub id: &'a str,
    pub workspace: &'a str,
    pub number: usize,
    pub label: &'a str,
    pub zoomed: bool,
    pub focused: bool,
    pub status: &'static str,
}

#[derive(Debug, Serialize)]
pub struct Pane<'a> {
    pub id: &'a str,
    pub workspace: &'a str,
    pub tab: &'a str,
    pub label: Option<&'a str>,
    pub cwd: Option<&'a str>,
    pub focused: bool,
}

#[derive(Debug, Serialize)]
pub struct Agent<'a> {
    pub pane: &'a str,
    pub workspace: &'a str,
    pub tab: &'a str,
    pub name: Option<&'a str>,
    pub agent: Option<&'a str>,
    pub title: Option<&'a str>,
    pub status: &'static str,
    pub focused: bool,
}

pub fn status(status: AgentStatus) -> &'static str {
    match status {
        AgentStatus::Idle => "idle",
        AgentStatus::Working => "working",
        AgentStatus::Blocked => "blocked",
        AgentStatus::Done => "done",
        AgentStatus::Unknown => "unknown",
    }
}

impl<'a> From<&'a ClientShellSnapshot> for ShellView<'a> {
    fn from(snapshot: &'a ClientShellSnapshot) -> Self {
        let mut agents: Vec<_> = snapshot.agents.iter().collect();
        // The daemon's agent order, with any agent it left out at the end.
        agents.sort_by_key(|agent| {
            snapshot
                .agent_order
                .iter()
                .position(|pane| *pane == agent.pane_id)
                .unwrap_or(usize::MAX)
        });
        Self {
            revision: snapshot.revision,
            focused_workspace: snapshot.focused_workspace_id.as_deref(),
            focused_tab: snapshot.focused_tab_id.as_deref(),
            focused_pane: snapshot.focused_pane_id.as_deref(),
            update_available: snapshot.update_available.as_deref(),
            workspaces: snapshot
                .workspaces
                .iter()
                .map(|w| Workspace {
                    id: &w.workspace_id,
                    number: w.number,
                    label: &w.label,
                    branch: w.branch.as_deref(),
                    ahead: w.git_ahead_behind.map(|(ahead, _)| ahead),
                    behind: w.git_ahead_behind.map(|(_, behind)| behind),
                    worktree: w.worktree.as_ref().map(|t| t.label.as_str()),
                    linked_worktree: w.worktree.as_ref().is_some_and(|t| t.is_linked_worktree),
                    active_tab: &w.active_tab_id,
                    focused: w.focused,
                    status: status(w.agent_status),
                })
                .collect(),
            tabs: snapshot
                .tabs
                .iter()
                .map(|t| Tab {
                    id: &t.tab_id,
                    workspace: &t.workspace_id,
                    number: t.number,
                    label: &t.label,
                    zoomed: t.zoomed,
                    focused: t.focused,
                    status: status(t.agent_status),
                })
                .collect(),
            panes: snapshot
                .panes
                .iter()
                .map(|p| Pane {
                    id: &p.pane_id,
                    workspace: &p.workspace_id,
                    tab: &p.tab_id,
                    label: p.label.as_deref(),
                    cwd: p.foreground_cwd.as_deref().or(p.cwd.as_deref()),
                    focused: p.focused,
                })
                .collect(),
            agents: agents
                .into_iter()
                .map(|a| Agent {
                    pane: &a.pane_id,
                    workspace: &a.workspace_id,
                    tab: &a.tab_id,
                    name: a.name.as_deref(),
                    agent: a.display_agent.as_deref().or(a.agent.as_deref()),
                    title: a.title.as_deref().or(a.terminal_title_stripped.as_deref()),
                    status: status(a.agent_status),
                    focused: a.focused,
                })
                .collect(),
        }
    }
}

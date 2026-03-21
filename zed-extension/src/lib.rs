use zed_extension_api as zed;

struct IntelliJScalaLsp;

impl zed::Extension for IntelliJScalaLsp {
    fn new() -> Self {
        IntelliJScalaLsp
    }

    fn language_server_command(
        &mut self,
        _language_server_id: &zed::LanguageServerId,
        _worktree: &zed::Worktree,
    ) -> zed::Result<zed::Command> {
        let path = std::env::var("INTELLIJ_SCALA_LSP_PATH")
            .unwrap_or_else(|_| "intellij-scala-lsp".to_string());

        Ok(zed::Command {
            command: path,
            args: vec![],
            env: vec![],
        })
    }
}

zed::register_extension!(IntelliJScalaLsp);

package com.ayush.Anchor;

// ─────────────────────────────────────────────────────────────────────────────
// PICOCLI IMPORTS
// ─────────────────────────────────────────────────────────────────────────────
// CommandLine     → the picocli engine. You hand it your command class and it
//                   parses argv, routes to subcommands, prints help, etc.
//
// @Command        → annotation that marks a class AS a CLI command (or the root).
//                   Like @RestController marks a class as a web endpoint in Spring.
//
// @Spec           → injects the CommandSpec (metadata about this command)
//                   so we can print help manually when no subcommand is given.
// ─────────────────────────────────────────────────────────────────────────────
import com.ayush.Anchor.cli.AddFolderCommand;
import com.ayush.Anchor.cli.StatusCommand;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Spec;

// ─────────────────────────────────────────────────────────────────────────────
// @Command — THE ROOT COMMAND ANNOTATION
// ─────────────────────────────────────────────────────────────────────────────
// name         : what the user types. e.g. "anchor" → `anchor add-folder ...`
// mixinStandardHelpOptions : automatically adds --help and --version flags
//                            (so we don't have to implement them manually)
// version      : shown when user runs `anchor --version`
// description  : shown in the auto-generated help text
// subcommands  : list of subcommand classes picocli should register.
//                Think of these like child routes in a Spring router.
// ─────────────────────────────────────────────────────────────────────────────
@Command(
    name = "anchor",
    mixinStandardHelpOptions = true,
    version = "Anchor 1.0",
    description = {
        "",
        "@|bold,cyan  ⚓ Anchor — Windows Upload Stabilizer|@",
        "  Watches a folder and uploads files to Google Drive",
        "  with resumable uploads. Never restart from zero again.",
        ""
    },
    subcommands = {
        AddFolderCommand.class,
        StatusCommand.class,
        // We'll add PauseCommand and ResumeCommand in Phase 2
        CommandLine.HelpCommand.class   // adds a `help` subcommand for free
    }
)
public class Main implements Runnable {

    // ─────────────────────────────────────────────────────────────────────────
    // @Spec
    // ─────────────────────────────────────────────────────────────────────────
    // picocli injects the CommandSpec for THIS command here automatically.
    // CommandSpec is the metadata object: it knows the command's name, all its
    // subcommands, options, etc. We use it to print usage when no subcommand
    // is provided — without it we'd need to pass `args` around manually.
    // ─────────────────────────────────────────────────────────────────────────
    @Spec
    CommandSpec spec;

    // ─────────────────────────────────────────────────────────────────────────
    // run() — called when the user types just "anchor" with no subcommand
    // ─────────────────────────────────────────────────────────────────────────
    // Picocli's Runnable contract: if user provides no subcommand,
    // run() is invoked on the root command. We use it to print help
    // instead of silently doing nothing.
    // ─────────────────────────────────────────────────────────────────────────
    @Override
    public void run() {
        // spec.commandLine() gives us the live CommandLine instance,
        // from which we get the UsageMessage to print to stderr.
        spec.commandLine().usage(System.err);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // main() — the real Java entry point
    // ─────────────────────────────────────────────────────────────────────────
    // new CommandLine(new Main())  → creates the picocli engine with our root
    //                                command as the starting point
    // .execute(args)               → parses args, routes to correct subcommand,
    //                                calls run() or call(), handles exceptions
    //
    // System.exit(...)             → exits with the correct exit code.
    //                                0 = success, non-zero = error.
    //                                Important for scripting (e.g., CI pipelines
    //                                that check exit codes).
    // ─────────────────────────────────────────────────────────────────────────
    public static void main(String[] args) {
        int exitCode = new CommandLine(new Main()).execute(args);
        System.exit(exitCode);
    }
}

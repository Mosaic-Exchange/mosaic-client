# mosaic-client

Mosaic is a desktop chatbot client built with JavaFX 21. Users can select an AI or network-based expert, start chat sessions, send messages, view chat history, and manage settings. The client communicates with `exchange-server` and `llm-server` backends, but those integrations are handled separately; this repo contains only the client UI.

## How to run

Requires Java 21 and Maven 3.8+.

```bash
mvn clean javafx:run
```

The app launches with a splash screen. Clicking **Get Started** navigates to the Main Workspace.

## Project structure

```
src/main/java/com/mosaic/client/
  MosaicApp.java                     # Entry point
  MainLayoutController.java          # Navigation shell (BorderPane host)
  Navigator.java                     # Static helper — call Navigator.showX() from any controller
  ui/screens/
    splash/     SplashController.java
    workspace/  MainWorkspaceController.java   # TODO #11-15
    expert/     ExpertSelectionController.java # TODO #8
    settings/   SettingsController.java        # TODO #9

src/main/resources/
  fxml/
    MainLayout.fxml
    SplashScreen.fxml
    WorkspaceScreen.fxml             # placeholder — see #11
    ExpertSelectionScreen.fxml       # placeholder — see #8
    SettingsScreen.fxml              # placeholder — see #9
  css/
    app.css                          # shared stylesheet (see #10)
```

## Navigation

All screen switches go through `Navigator`:

```java
Navigator.showWorkspace();
Navigator.showExpertSelection();
Navigator.showSettings();
Navigator.showSplash();
```

## Contributing

Please follow the following procedure when making any changes to the repository:

1. [Create an issue on Github.][0]
2. Under "**Development**" on the right side of the issue overview, click "**Create a branch**".
3. In most cases, the default branch name should suffice. If not, **make sure to keep
   the issue number and dash at the beginning of the branch name**.
4. Switch to the new branch locally with
```bash
git fetch origin
git switch [branch name here]
```
   (or by refreshing the repository and selecting the new branch in your IDE).

5. Do the work. Make sure to create automated tests along the way.
6. Push your work and open a pull request (the button should appear on the [repository
   page][1]).
7. Check the results of the CI pipeline in the pull request overview. If there are
   errors, correct them and push the changes.
8. Respond to reviewer feedback.
9. Merge.

[0]: https://github.com/Mosaic-Exchange/mosaic-client/issues/new/choose
[1]: https://github.com/Mosaic-Exchange/mosaic-client

// DSHA_SHELL_INTEGRATION_POLICY_V1: the signed APK owns these adapters.
// Keep their profile layers and Loader fibers available to the shell.
const dshaShellBundles = new Set(["dsh-app-integration", "dsh-computer-use-android"]);
function dshaIsShellBundle(name) {
 return dshaShellBundles.has(name);
}
function dshaIsShellEntry(name, id) {
 return dshaIsShellBundle(name) || (name === "@deepseek-ai/dsh-computer-use"
  && (id === "dsha-computer-use-service" || id === "include:dsha-computer-use-service"));
}

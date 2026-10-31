# Local recovery acceptance helpers

These Java files are **manual lab fixtures**, excluded from production source sets. They do not establish release admission, genuine CI provenance or native button interaction. Do not use them on a user's installation.

`HostRecoveryFixture` is bound to `D:/turboism-test/issue50-recovery-f173f64`. `HostRollbackFixture` refuses any other root. If reproducing elsewhere, deliberately adapt the guards and create a new isolated home first. Never disable production eligibility checks or rewrite artifact provenance.

Compile with JDK 17, the current staged agent and its SDK closure:

```powershell
$cp = '<repo>\build\windows-installer\staging\turboism-agent.jar;<repo>\build\windows-installer\staging\graal\lib\*;<fixture classes>'
javac -cp $cp -d '<fixture classes>' HostRecoveryFixture.java HostRollbackFixture.java
```

They generate no production credential. The README-only component helper generates an ephemeral key in its own JVM and authenticates its fixture manifest there; the fixture manifest counter is not an official CI build number. It neither writes CI properties nor installs a trusted-key resource into Cubism's agent.

Both sources compiled successfully against `f173f64e2` staging on the validation Windows machine. Their present lab state and safe continuation are recorded in [the handoff](../HANDOFF.zh-CN.md). Do not re-seed the currently retained failed transaction.

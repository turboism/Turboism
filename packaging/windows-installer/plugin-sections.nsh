; 由 assemble-release.sh 按 release-plugins.txt 权威清单生成，勿手改。
; Full 变体安装全部第一方插件 JAR；Thin 变体不携带任何插件 JAR。

!if "${VARIANT}" != "thin"
Section "-插件载荷" SecPluginPayload
  nsExec::ExecToLog '"$SYSDIR\WindowsPowerShell\v1.0\powershell.exe" -NoProfile -NonInteractive -ExecutionPolicy Bypass -File "$INSTDIR\install-jar-payload.ps1" -ManifestPath "$PLUGINSDIR\Turboism-payload-manifests\payload-plugins.sha256" -DestinationRoot "$INSTDIR" -PlanRoot "$PLUGINSDIR\Turboism-plugin-plan" -PlanOnly'
  Pop $0
  ${If} $0 != 0
    MessageBox MB_ICONSTOP "$(PayloadInstallError)"
    Abort
  ${EndIf}
  ${If} ${FileExists} "$PLUGINSDIR\Turboism-plugin-plan\0000.need"
    SetOutPath "$PLUGINSDIR\Turboism-plugin-payload\plugins"
    File "/oname=acp.jar" "${STAGING_DIR}/plugins/acp.jar"
  ${EndIf}
  ${If} ${FileExists} "$PLUGINSDIR\Turboism-plugin-plan\0001.need"
    SetOutPath "$PLUGINSDIR\Turboism-plugin-payload\plugins"
    File "/oname=boundingbox-warp-mirror.jar" "${STAGING_DIR}/plugins/boundingbox-warp-mirror.jar"
  ${EndIf}
  ${If} ${FileExists} "$PLUGINSDIR\Turboism-plugin-plan\0002.need"
    SetOutPath "$PLUGINSDIR\Turboism-plugin-payload\plugins"
    File "/oname=clipmask-viewer.jar" "${STAGING_DIR}/plugins/clipmask-viewer.jar"
  ${EndIf}
  ${If} ${FileExists} "$PLUGINSDIR\Turboism-plugin-plan\0003.need"
    SetOutPath "$PLUGINSDIR\Turboism-plugin-payload\plugins"
    File "/oname=command-palette.jar" "${STAGING_DIR}/plugins/command-palette.jar"
  ${EndIf}
  ${If} ${FileExists} "$PLUGINSDIR\Turboism-plugin-plan\0004.need"
    SetOutPath "$PLUGINSDIR\Turboism-plugin-payload\plugins"
    File "/oname=cubism-tab-filter.jar" "${STAGING_DIR}/plugins/cubism-tab-filter.jar"
  ${EndIf}
  ${If} ${FileExists} "$PLUGINSDIR\Turboism-plugin-plan\0005.need"
    SetOutPath "$PLUGINSDIR\Turboism-plugin-payload\plugins"
    File "/oname=external-psd-edit.jar" "${STAGING_DIR}/plugins/external-psd-edit.jar"
  ${EndIf}
  ${If} ${FileExists} "$PLUGINSDIR\Turboism-plugin-plan\0006.need"
    SetOutPath "$PLUGINSDIR\Turboism-plugin-payload\plugins"
    File "/oname=history-panel.jar" "${STAGING_DIR}/plugins/history-panel.jar"
  ${EndIf}
  ${If} ${FileExists} "$PLUGINSDIR\Turboism-plugin-plan\0007.need"
    SetOutPath "$PLUGINSDIR\Turboism-plugin-payload\plugins"
    File "/oname=mcp.jar" "${STAGING_DIR}/plugins/mcp.jar"
  ${EndIf}
  ${If} ${FileExists} "$PLUGINSDIR\Turboism-plugin-plan\0008.need"
    SetOutPath "$PLUGINSDIR\Turboism-plugin-payload\plugins"
    File "/oname=mesh-edit-mirror-axis-enhance.jar" "${STAGING_DIR}/plugins/mesh-edit-mirror-axis-enhance.jar"
  ${EndIf}
  ${If} ${FileExists} "$PLUGINSDIR\Turboism-plugin-plan\0009.need"
    SetOutPath "$PLUGINSDIR\Turboism-plugin-payload\plugins"
    File "/oname=model-import-3d.jar" "${STAGING_DIR}/plugins/model-import-3d.jar"
  ${EndIf}
  ${If} ${FileExists} "$PLUGINSDIR\Turboism-plugin-plan\0010.need"
    SetOutPath "$PLUGINSDIR\Turboism-plugin-payload\plugins"
    File "/oname=palette-label-style.jar" "${STAGING_DIR}/plugins/palette-label-style.jar"
  ${EndIf}
  ${If} ${FileExists} "$PLUGINSDIR\Turboism-plugin-plan\0011.need"
    SetOutPath "$PLUGINSDIR\Turboism-plugin-payload\plugins"
    File "/oname=parameter-batch-transfer.jar" "${STAGING_DIR}/plugins/parameter-batch-transfer.jar"
  ${EndIf}
  ${If} ${FileExists} "$PLUGINSDIR\Turboism-plugin-plan\0012.need"
    SetOutPath "$PLUGINSDIR\Turboism-plugin-payload\plugins"
    File "/oname=perf-stats.jar" "${STAGING_DIR}/plugins/perf-stats.jar"
  ${EndIf}
  ${If} ${FileExists} "$PLUGINSDIR\Turboism-plugin-plan\0013.need"
    SetOutPath "$PLUGINSDIR\Turboism-plugin-payload\plugins"
    File "/oname=physics-editor.jar" "${STAGING_DIR}/plugins/physics-editor.jar"
  ${EndIf}
  ${If} ${FileExists} "$PLUGINSDIR\Turboism-plugin-plan\0014.need"
    SetOutPath "$PLUGINSDIR\Turboism-plugin-payload\plugins"
    File "/oname=protected-export.jar" "${STAGING_DIR}/plugins/protected-export.jar"
  ${EndIf}
  ${If} ${FileExists} "$PLUGINSDIR\Turboism-plugin-plan\0015.need"
    SetOutPath "$PLUGINSDIR\Turboism-plugin-payload\plugins"
    File "/oname=psd-clip-mask-import.jar" "${STAGING_DIR}/plugins/psd-clip-mask-import.jar"
  ${EndIf}
  ${If} ${FileExists} "$PLUGINSDIR\Turboism-plugin-plan\0016.need"
    SetOutPath "$PLUGINSDIR\Turboism-plugin-payload\plugins"
    File "/oname=recent-preview.jar" "${STAGING_DIR}/plugins/recent-preview.jar"
  ${EndIf}
  ${If} ${FileExists} "$PLUGINSDIR\Turboism-plugin-plan\0017.need"
    SetOutPath "$PLUGINSDIR\Turboism-plugin-payload\plugins"
    File "/oname=scene-palette-enhancer.jar" "${STAGING_DIR}/plugins/scene-palette-enhancer.jar"
  ${EndIf}
  ${If} ${FileExists} "$PLUGINSDIR\Turboism-plugin-plan\0018.need"
    SetOutPath "$PLUGINSDIR\Turboism-plugin-payload\plugins"
    File "/oname=selection-brush.jar" "${STAGING_DIR}/plugins/selection-brush.jar"
  ${EndIf}
  ${If} ${FileExists} "$PLUGINSDIR\Turboism-plugin-plan\0019.need"
    SetOutPath "$PLUGINSDIR\Turboism-plugin-payload\plugins"
    File "/oname=atlas-maxrects-bssf.jar" "${STAGING_DIR}/plugins/atlas-maxrects-bssf.jar"
  ${EndIf}
  ${If} ${FileExists} "$PLUGINSDIR\Turboism-plugin-plan\0020.need"
    SetOutPath "$PLUGINSDIR\Turboism-plugin-payload\plugins"
    File "/oname=texture-atlas-stats.jar" "${STAGING_DIR}/plugins/texture-atlas-stats.jar"
  ${EndIf}
  ${If} ${FileExists} "$PLUGINSDIR\Turboism-plugin-plan\0021.need"
    SetOutPath "$PLUGINSDIR\Turboism-plugin-payload\plugins"
    File "/oname=ui-theme.jar" "${STAGING_DIR}/plugins/ui-theme.jar"
  ${EndIf}
  ${If} ${FileExists} "$PLUGINSDIR\Turboism-plugin-plan\0022.need"
    SetOutPath "$PLUGINSDIR\Turboism-plugin-payload\plugins"
    File "/oname=warp-deformer-alt-symmetry.jar" "${STAGING_DIR}/plugins/warp-deformer-alt-symmetry.jar"
  ${EndIf}
  ${If} ${FileExists} "$PLUGINSDIR\Turboism-plugin-plan\0023.need"
    SetOutPath "$PLUGINSDIR\Turboism-plugin-payload\plugins"
    File "/oname=webdav-backup.jar" "${STAGING_DIR}/plugins/webdav-backup.jar"
  ${EndIf}
  nsExec::ExecToLog '"$SYSDIR\WindowsPowerShell\v1.0\powershell.exe" -NoProfile -NonInteractive -ExecutionPolicy Bypass -File "$INSTDIR\install-jar-payload.ps1" -SourceRoot "$PLUGINSDIR\Turboism-plugin-payload" -ManifestPath "$PLUGINSDIR\Turboism-payload-manifests\payload-plugins.sha256" -DestinationRoot "$INSTDIR"'
  Pop $0
  ${If} $0 != 0
    MessageBox MB_ICONSTOP "$(PayloadInstallError)"
    Abort
  ${EndIf}
  RMDir /r "$PLUGINSDIR\Turboism-plugin-payload"
  RMDir /r "$PLUGINSDIR\Turboism-plugin-plan"
  Delete "$PLUGINSDIR\Turboism-payload-manifests\payload-plugins.sha256"
  RMDir "$PLUGINSDIR\Turboism-payload-manifests"
SectionEnd
!endif

!if "${VARIANT}" != "thin"
; 导出完整捆绑插件 id 清单，供前置配置提交保留无关禁用项。
Function SetBundledPluginIds
  StrCpy $bundledPluginIds "dev.turboism.plugin.acp;dev.turboism.plugin.boundingbox-warp-mirror;dev.turboism.plugin.clipmask-viewer;dev.turboism.plugin.command-palette;dev.turboism.plugin.cubism-tab-filter;dev.turboism.plugin.external-psd-edit;dev.turboism.plugin.historypanel;dev.turboism.plugin.mcp;dev.turboism.plugin.mesh-edit-mirror-axis-enhance;dev.turboism.plugin.model-import-3d;dev.turboism.plugin.palette-label-style;dev.turboism.plugin.parameter-batch-transfer;dev.turboism.plugin.perf-stats;dev.turboism.plugin.physics-editor;dev.turboism.plugin.protected-export;dev.turboism.plugin.psd-clip-mask-import;dev.turboism.plugin.recent-preview;dev.turboism.plugin.scene-palette-enhancer;dev.turboism.plugin.selection-brush;dev.turboism.plugin.texture-atlas;dev.turboism.plugin.texture-atlas-stats;dev.turboism.plugin.uitheme;dev.turboism.plugin.warp-deformer-alt-symmetry;dev.turboism.plugin.webdav"
FunctionEnd
!endif

; 卸载白名单：仅删除 Full 安装器部署的插件 JAR；未知文件与第三方 JAR 保留。
Function un.DeleteInstallerPluginJars
!if "${VARIANT}" != "thin"
  Delete "$INSTDIR\plugins\acp.jar"
  Delete "$INSTDIR\plugins\atlas-maxrects-bssf.jar"
  Delete "$INSTDIR\plugins\boundingbox-warp-mirror.jar"
  Delete "$INSTDIR\plugins\clipmask-viewer.jar"
  Delete "$INSTDIR\plugins\command-palette.jar"
  Delete "$INSTDIR\plugins\cubism-tab-filter.jar"
  Delete "$INSTDIR\plugins\external-psd-edit.jar"
  Delete "$INSTDIR\plugins\history-panel.jar"
  Delete "$INSTDIR\plugins\mcp.jar"
  Delete "$INSTDIR\plugins\mesh-edit-mirror-axis-enhance.jar"
  Delete "$INSTDIR\plugins\model-import-3d.jar"
  Delete "$INSTDIR\plugins\palette-label-style.jar"
  Delete "$INSTDIR\plugins\parameter-batch-transfer.jar"
  Delete "$INSTDIR\plugins\perf-stats.jar"
  Delete "$INSTDIR\plugins\physics-editor.jar"
  Delete "$INSTDIR\plugins\protected-export.jar"
  Delete "$INSTDIR\plugins\psd-clip-mask-import.jar"
  Delete "$INSTDIR\plugins\recent-preview.jar"
  Delete "$INSTDIR\plugins\scene-palette-enhancer.jar"
  Delete "$INSTDIR\plugins\selection-brush.jar"
  Delete "$INSTDIR\plugins\texture-atlas-stats.jar"
  Delete "$INSTDIR\plugins\ui-theme.jar"
  Delete "$INSTDIR\plugins\warp-deformer-alt-symmetry.jar"
  Delete "$INSTDIR\plugins\webdav-backup.jar"
!endif
FunctionEnd


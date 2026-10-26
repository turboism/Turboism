; 由 assemble-release.sh 按 release-plugins.txt 权威清单生成，勿手改。
; 标准安装器携带全部第一方插件 JAR。

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
    File "/oname=artmesh-mirror.jar" "${STAGING_DIR}/plugins/artmesh-mirror.jar"
  ${EndIf}
  ${If} ${FileExists} "$PLUGINSDIR\Turboism-plugin-plan\0002.need"
    SetOutPath "$PLUGINSDIR\Turboism-plugin-payload\plugins"
    File "/oname=atlas-bitmask-nesting.jar" "${STAGING_DIR}/plugins/atlas-bitmask-nesting.jar"
  ${EndIf}
  ${If} ${FileExists} "$PLUGINSDIR\Turboism-plugin-plan\0003.need"
    SetOutPath "$PLUGINSDIR\Turboism-plugin-payload\plugins"
    File "/oname=atlas-dalsoo-polygon.jar" "${STAGING_DIR}/plugins/atlas-dalsoo-polygon.jar"
  ${EndIf}
  ${If} ${FileExists} "$PLUGINSDIR\Turboism-plugin-plan\0004.need"
    SetOutPath "$PLUGINSDIR\Turboism-plugin-payload\plugins"
    File "/oname=atlas-folder-layout.jar" "${STAGING_DIR}/plugins/atlas-folder-layout.jar"
  ${EndIf}
  ${If} ${FileExists} "$PLUGINSDIR\Turboism-plugin-plan\0005.need"
    SetOutPath "$PLUGINSDIR\Turboism-plugin-payload\plugins"
    File "/oname=atlas-maxrects-bssf.jar" "${STAGING_DIR}/plugins/atlas-maxrects-bssf.jar"
  ${EndIf}
  ${If} ${FileExists} "$PLUGINSDIR\Turboism-plugin-plan\0006.need"
    SetOutPath "$PLUGINSDIR\Turboism-plugin-payload\plugins"
    File "/oname=boundingbox-warp-mirror.jar" "${STAGING_DIR}/plugins/boundingbox-warp-mirror.jar"
  ${EndIf}
  ${If} ${FileExists} "$PLUGINSDIR\Turboism-plugin-plan\0007.need"
    SetOutPath "$PLUGINSDIR\Turboism-plugin-payload\plugins"
    File "/oname=clipmask-viewer.jar" "${STAGING_DIR}/plugins/clipmask-viewer.jar"
  ${EndIf}
  ${If} ${FileExists} "$PLUGINSDIR\Turboism-plugin-plan\0008.need"
    SetOutPath "$PLUGINSDIR\Turboism-plugin-payload\plugins"
    File "/oname=command-palette.jar" "${STAGING_DIR}/plugins/command-palette.jar"
  ${EndIf}
  ${If} ${FileExists} "$PLUGINSDIR\Turboism-plugin-plan\0009.need"
    SetOutPath "$PLUGINSDIR\Turboism-plugin-payload\plugins"
    File "/oname=cubism-tab-filter.jar" "${STAGING_DIR}/plugins/cubism-tab-filter.jar"
  ${EndIf}
  ${If} ${FileExists} "$PLUGINSDIR\Turboism-plugin-plan\0010.need"
    SetOutPath "$PLUGINSDIR\Turboism-plugin-payload\plugins"
    File "/oname=deformer-batch-rename.jar" "${STAGING_DIR}/plugins/deformer-batch-rename.jar"
  ${EndIf}
  ${If} ${FileExists} "$PLUGINSDIR\Turboism-plugin-plan\0011.need"
    SetOutPath "$PLUGINSDIR\Turboism-plugin-payload\plugins"
    File "/oname=external-psd-edit.jar" "${STAGING_DIR}/plugins/external-psd-edit.jar"
  ${EndIf}
  ${If} ${FileExists} "$PLUGINSDIR\Turboism-plugin-plan\0012.need"
    SetOutPath "$PLUGINSDIR\Turboism-plugin-payload\plugins"
    File "/oname=frame-animation-import.jar" "${STAGING_DIR}/plugins/frame-animation-import.jar"
  ${EndIf}
  ${If} ${FileExists} "$PLUGINSDIR\Turboism-plugin-plan\0013.need"
    SetOutPath "$PLUGINSDIR\Turboism-plugin-payload\plugins"
    File "/oname=history-panel.jar" "${STAGING_DIR}/plugins/history-panel.jar"
  ${EndIf}
  ${If} ${FileExists} "$PLUGINSDIR\Turboism-plugin-plan\0014.need"
    SetOutPath "$PLUGINSDIR\Turboism-plugin-payload\plugins"
    File "/oname=mcp.jar" "${STAGING_DIR}/plugins/mcp.jar"
  ${EndIf}
  ${If} ${FileExists} "$PLUGINSDIR\Turboism-plugin-plan\0015.need"
    SetOutPath "$PLUGINSDIR\Turboism-plugin-payload\plugins"
    File "/oname=mesh-edit-mirror-axis-enhance.jar" "${STAGING_DIR}/plugins/mesh-edit-mirror-axis-enhance.jar"
  ${EndIf}
  ${If} ${FileExists} "$PLUGINSDIR\Turboism-plugin-plan\0016.need"
    SetOutPath "$PLUGINSDIR\Turboism-plugin-payload\plugins"
    File "/oname=model-import-3d.jar" "${STAGING_DIR}/plugins/model-import-3d.jar"
  ${EndIf}
  ${If} ${FileExists} "$PLUGINSDIR\Turboism-plugin-plan\0017.need"
    SetOutPath "$PLUGINSDIR\Turboism-plugin-payload\plugins"
    File "/oname=palette-label-style.jar" "${STAGING_DIR}/plugins/palette-label-style.jar"
  ${EndIf}
  ${If} ${FileExists} "$PLUGINSDIR\Turboism-plugin-plan\0018.need"
    SetOutPath "$PLUGINSDIR\Turboism-plugin-payload\plugins"
    File "/oname=parameter-batch-transfer.jar" "${STAGING_DIR}/plugins/parameter-batch-transfer.jar"
  ${EndIf}
  ${If} ${FileExists} "$PLUGINSDIR\Turboism-plugin-plan\0019.need"
    SetOutPath "$PLUGINSDIR\Turboism-plugin-payload\plugins"
    File "/oname=parameter-sync.jar" "${STAGING_DIR}/plugins/parameter-sync.jar"
  ${EndIf}
  ${If} ${FileExists} "$PLUGINSDIR\Turboism-plugin-plan\0020.need"
    SetOutPath "$PLUGINSDIR\Turboism-plugin-payload\plugins"
    File "/oname=perf-stats.jar" "${STAGING_DIR}/plugins/perf-stats.jar"
  ${EndIf}
  ${If} ${FileExists} "$PLUGINSDIR\Turboism-plugin-plan\0021.need"
    SetOutPath "$PLUGINSDIR\Turboism-plugin-payload\plugins"
    File "/oname=physics-editor.jar" "${STAGING_DIR}/plugins/physics-editor.jar"
  ${EndIf}
  ${If} ${FileExists} "$PLUGINSDIR\Turboism-plugin-plan\0022.need"
    SetOutPath "$PLUGINSDIR\Turboism-plugin-payload\plugins"
    File "/oname=protected-export.jar" "${STAGING_DIR}/plugins/protected-export.jar"
  ${EndIf}
  ${If} ${FileExists} "$PLUGINSDIR\Turboism-plugin-plan\0023.need"
    SetOutPath "$PLUGINSDIR\Turboism-plugin-payload\plugins"
    File "/oname=psd-clip-mask-import.jar" "${STAGING_DIR}/plugins/psd-clip-mask-import.jar"
  ${EndIf}
  ${If} ${FileExists} "$PLUGINSDIR\Turboism-plugin-plan\0024.need"
    SetOutPath "$PLUGINSDIR\Turboism-plugin-payload\plugins"
    File "/oname=recent-preview.jar" "${STAGING_DIR}/plugins/recent-preview.jar"
  ${EndIf}
  ${If} ${FileExists} "$PLUGINSDIR\Turboism-plugin-plan\0025.need"
    SetOutPath "$PLUGINSDIR\Turboism-plugin-payload\plugins"
    File "/oname=scene-palette-enhancer.jar" "${STAGING_DIR}/plugins/scene-palette-enhancer.jar"
  ${EndIf}
  ${If} ${FileExists} "$PLUGINSDIR\Turboism-plugin-plan\0026.need"
    SetOutPath "$PLUGINSDIR\Turboism-plugin-payload\plugins"
    File "/oname=selection-brush.jar" "${STAGING_DIR}/plugins/selection-brush.jar"
  ${EndIf}
  ${If} ${FileExists} "$PLUGINSDIR\Turboism-plugin-plan\0027.need"
    SetOutPath "$PLUGINSDIR\Turboism-plugin-payload\plugins"
    File "/oname=texture-atlas-stats.jar" "${STAGING_DIR}/plugins/texture-atlas-stats.jar"
  ${EndIf}
  ${If} ${FileExists} "$PLUGINSDIR\Turboism-plugin-plan\0028.need"
    SetOutPath "$PLUGINSDIR\Turboism-plugin-payload\plugins"
    File "/oname=ui-theme.jar" "${STAGING_DIR}/plugins/ui-theme.jar"
  ${EndIf}
  ${If} ${FileExists} "$PLUGINSDIR\Turboism-plugin-plan\0029.need"
    SetOutPath "$PLUGINSDIR\Turboism-plugin-payload\plugins"
    File "/oname=warp-deformer-alt-symmetry.jar" "${STAGING_DIR}/plugins/warp-deformer-alt-symmetry.jar"
  ${EndIf}
  ${If} ${FileExists} "$PLUGINSDIR\Turboism-plugin-plan\0030.need"
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

LangString PLUGIN_NAME_dev_turboism_plugin_acp ${LANG_ENGLISH} "Turboism ACP 0.1.0"
LangString PLUGIN_DESC_dev_turboism_plugin_acp ${LANG_ENGLISH} "ACP v1 Agent and Settings windows connecting a user-installed agent to the authenticated Turboism MCP server."
LangString PLUGIN_NAME_dev_turboism_plugin_acp ${LANG_SIMPCHINESE} "Turboism ACP æºè½ä½ 0.1.0"
LangString PLUGIN_DESC_dev_turboism_plugin_acp ${LANG_SIMPCHINESE} "å°ç¨æ·å®è£ç ACP v1 Agent è¿æ¥å°å·²è®¤è¯ç Turboism MCP æå¡å¨ç Agent/è®¾ç½®çªå£ã"
LangString PLUGIN_NAME_dev_turboism_plugin_acp ${LANG_JAPANESE} "Turboism ACP ã¨ã¼ã¸ã§ã³ã 0.1.0"
LangString PLUGIN_DESC_dev_turboism_plugin_acp ${LANG_JAPANESE} "ã¦ã¼ã¶ã¼ãã¤ã³ã¹ãã¼ã«ãã ACP v1 ã¨ã¼ã¸ã§ã³ããèªè¨¼æ¸ã¿ Turboism MCP ãµã¼ãã¼ã«æ¥ç¶ããã¨ã¼ã¸ã§ã³ã/è¨­å®ã¦ã£ã³ãã¦ã"
LangString PLUGIN_NAME_dev_turboism_plugin_acp ${LANG_KOREAN} "Turboism ACP ìì´ì í¸ 0.1.0"
LangString PLUGIN_DESC_dev_turboism_plugin_acp ${LANG_KOREAN} "ì¬ì©ìê° ì¤ì¹í ACP v1 ìì´ì í¸ë¥¼ ì¸ì¦ë Turboism MCP ìë²ì ì°ê²°íë ìì´ì í¸/ì¤ì  ì°½."
LangString PLUGIN_NAME_dev_turboism_plugin_artmesh_mirror ${LANG_ENGLISH} "ArtMesh Mirror 0.1.0"
LangString PLUGIN_DESC_dev_turboism_plugin_artmesh_mirror ${LANG_ENGLISH} "Independent ArtMesh mirror editing with a freely movable and rotatable axis."
LangString PLUGIN_NAME_dev_turboism_plugin_artmesh_mirror ${LANG_SIMPCHINESE} "å¾å½¢ç½æ ¼éå 0.1.0"
LangString PLUGIN_DESC_dev_turboism_plugin_artmesh_mirror ${LANG_SIMPCHINESE} "ç¬ç«çå¾å½¢ç½æ ¼éåç¼è¾ï¼æ¯æèªç±ç§»å¨ä¸æè½¬éåè½´ã"
LangString PLUGIN_NAME_dev_turboism_plugin_artmesh_mirror ${LANG_JAPANESE} "ã¢ã¼ãã¡ãã·ã¥ãã©ã¼ 0.1.0"
LangString PLUGIN_DESC_dev_turboism_plugin_artmesh_mirror ${LANG_JAPANESE} "ç¬ç«ããã¢ã¼ãã¡ãã·ã¥ã®ãã©ã¼ç·¨éãå¯¾ç§°è»¸ãç§»åã»åè»¢ã§ãã¾ãã"
LangString PLUGIN_NAME_dev_turboism_plugin_artmesh_mirror ${LANG_KOREAN} "ìí¸ë©ì ë¯¸ë¬ 0.1.0"
LangString PLUGIN_DESC_dev_turboism_plugin_artmesh_mirror ${LANG_KOREAN} "ì¶ ì´ëê³¼ íì ì ì§ìíë ëë¦½ ìí¸ë©ì ë¯¸ë¬ í¸ì§ìëë¤."
LangString PLUGIN_NAME_dev_turboism_plugin_texture_atlas_nesting ${LANG_ENGLISH} "Bitmask Nesting 0.1.0"
LangString PLUGIN_DESC_dev_turboism_plugin_texture_atlas_nesting ${LANG_ENGLISH} "Dense texture-atlas packing with concave outlines and multiple rotation angles."
LangString PLUGIN_NAME_dev_turboism_plugin_texture_atlas_nesting ${LANG_SIMPCHINESE} "Bitmask Nesting å¯éè½®å»æç 0.1.0"
LangString PLUGIN_DESC_dev_turboism_plugin_texture_atlas_nesting ${LANG_SIMPCHINESE} "å©ç¨å¹è½®å»åå¤ä¸ªæè½¬è§åº¦è¿è¡å¯éçº¹çæçã"
LangString PLUGIN_NAME_dev_turboism_plugin_texture_atlas_nesting ${LANG_JAPANESE} "Bitmask Nesting è¼ªé­éç½® 0.1.0"
LangString PLUGIN_DESC_dev_turboism_plugin_texture_atlas_nesting ${LANG_JAPANESE} "å¹å½¢ç¶ã®è¼ªé­ã¨è¤æ°ã®åè»¢è§åº¦ãä½¿ã£ã¦ããã¯ã¹ãã£ãé«å¯åº¦ã«éç½®ãã¾ãã"
LangString PLUGIN_NAME_dev_turboism_plugin_texture_atlas_nesting ${LANG_KOREAN} "Bitmask Nesting ì¤ê³½ ë°°ì¹ 0.1.0"
LangString PLUGIN_DESC_dev_turboism_plugin_texture_atlas_nesting ${LANG_KOREAN} "ì¤ëª©í ì¤ê³½ê³¼ ì¬ë¬ íì  ê°ëë¥¼ ì¬ì©í´ íì¤ì²ë¥¼ ì¡°ë°íê² ë°°ì¹í©ëë¤."
LangString PLUGIN_NAME_dev_turboism_plugin_texture_atlas_dalsoo ${LANG_ENGLISH} "Dalsoo Polygon Packing Algorithm 0.1.0"
LangString PLUGIN_DESC_dev_turboism_plugin_texture_atlas_dalsoo ${LANG_ENGLISH} "Irregular-polygon texture-atlas packing (concave outlines, free rotation, per-item locks)."
LangString PLUGIN_NAME_dev_turboism_plugin_texture_atlas_dalsoo ${LANG_SIMPCHINESE} "Dalsoo å¤è¾¹å½¢æçç®æ³ 0.1.0"
LangString PLUGIN_DESC_dev_turboism_plugin_texture_atlas_dalsoo ${LANG_SIMPCHINESE} "ä¸è§åå¤è¾¹å½¢çº¹çéæçï¼æ¯æå¹è½®å»ãèªç±è§åº¦ãéé¡¹éå®ï¼ã"
LangString PLUGIN_NAME_dev_turboism_plugin_texture_atlas_dalsoo ${LANG_JAPANESE} "Dalsoo ããªã´ã³åå¡«ã¢ã«ã´ãªãºã  0.1.0"
LangString PLUGIN_DESC_dev_turboism_plugin_texture_atlas_dalsoo ${LANG_JAPANESE} "ä¸è¦åããªã´ã³ã®ãã¯ã¹ãã£ã¢ãã©ã¹åå¡«ï¼å¹ã¿è¼ªé­ã»èªç±åè»¢ã»ã¢ã¤ãã åä½ã®ã­ãã¯å¯¾å¿ï¼ã"
LangString PLUGIN_NAME_dev_turboism_plugin_texture_atlas_dalsoo ${LANG_KOREAN} "Dalsoo í´ë¦¬ê³¤ í¨í¹ ìê³ ë¦¬ì¦ 0.1.0"
LangString PLUGIN_DESC_dev_turboism_plugin_texture_atlas_dalsoo ${LANG_KOREAN} "ë¶ê·ì¹ í´ë¦¬ê³¤ íì¤ì² ìíë¼ì¤ í¨í¹(ì¤ëª© ì¤ê³½, ìì  íì , í­ëª©ë³ ì ê¸ ì§ì)."
LangString PLUGIN_NAME_dev_turboism_plugin_texture_atlas_folders ${LANG_ENGLISH} "Folder Atlas Layout 0.1.0"
LangString PLUGIN_DESC_dev_turboism_plugin_texture_atlas_folders ${LANG_ENGLISH} "Arrange texture pages by Part folders using registered packing algorithms."
LangString PLUGIN_NAME_dev_turboism_plugin_texture_atlas_folders ${LANG_SIMPCHINESE} "çº¹çéä¸é®æç 0.1.0"
LangString PLUGIN_DESC_dev_turboism_plugin_texture_atlas_folders ${LANG_SIMPCHINESE} "æé¨ä»¶æä»¶å¤¹åæ²»çæå¤å¼ çº¹çï¼èªå¨åç°å·²æ³¨åçæçç®æ³ã"
LangString PLUGIN_NAME_dev_turboism_plugin_texture_atlas_folders ${LANG_JAPANESE} "ãã©ã«ãã¼å¥ã¢ãã©ã¹éç½® 0.1.0"
LangString PLUGIN_DESC_dev_turboism_plugin_texture_atlas_folders ${LANG_JAPANESE} "ç»é²æ¸ã¿éç½®ã¢ã«ã´ãªãºã ãä½¿ãããã¼ããã©ã«ãã¼å¥ã«ã¢ãã©ã¹ãä½æãã¾ãã"
LangString PLUGIN_NAME_dev_turboism_plugin_texture_atlas_folders ${LANG_KOREAN} "í´ëë³ ìíë¼ì¤ ë°°ì¹ 0.1.0"
LangString PLUGIN_DESC_dev_turboism_plugin_texture_atlas_folders ${LANG_KOREAN} "ë±ë¡ë ë°°ì¹ ìê³ ë¦¬ì¦ì¼ë¡ íì¸  í´ëë³ íì¤ì² íì´ì§ë¥¼ ìì±í©ëë¤."
LangString PLUGIN_NAME_dev_turboism_plugin_texture_atlas ${LANG_ENGLISH} "MaxRects-BSSF Layout Algorithm 0.1.0"
LangString PLUGIN_DESC_dev_turboism_plugin_texture_atlas ${LANG_ENGLISH} "Registers the MaxRects-BSSF texture-atlas packing algorithm with parallel search."
LangString PLUGIN_NAME_dev_turboism_plugin_texture_atlas ${LANG_SIMPCHINESE} "MaxRects-BSSF å¸å±ç®æ³ 0.1.0"
LangString PLUGIN_DESC_dev_turboism_plugin_texture_atlas ${LANG_SIMPCHINESE} "Turboism çMaxRects-BSSF å¸å±ç®æ³ã"
LangString PLUGIN_NAME_dev_turboism_plugin_texture_atlas ${LANG_JAPANESE} "MaxRects-BSSF ã¬ã¤ã¢ã¦ãã¢ã«ã´ãªãºã  0.1.0"
LangString PLUGIN_DESC_dev_turboism_plugin_texture_atlas ${LANG_JAPANESE} "Turboism ã®MaxRects-BSSF ã¬ã¤ã¢ã¦ãã¢ã«ã´ãªãºã ã"
LangString PLUGIN_NAME_dev_turboism_plugin_texture_atlas ${LANG_KOREAN} "MaxRects-BSSF ë ì´ìì ìê³ ë¦¬ì¦ 0.1.0"
LangString PLUGIN_DESC_dev_turboism_plugin_texture_atlas ${LANG_KOREAN} "Turboism MaxRects-BSSF ë ì´ìì ìê³ ë¦¬ì¦ íë¬ê·¸ì¸ìëë¤."
LangString PLUGIN_NAME_dev_turboism_plugin_boundingbox_warp_mirror ${LANG_ENGLISH} "BoundingBox Warp Mirror 0.1.0"
LangString PLUGIN_DESC_dev_turboism_plugin_boundingbox_warp_mirror ${LANG_ENGLISH} "Adds a BoundingBox overlay button that opens a mirror-direction dialog and applies a one-shot whole-object Warp Deformer mirror, optionally preserving descendant geometry."
LangString PLUGIN_NAME_dev_turboism_plugin_boundingbox_warp_mirror ${LANG_SIMPCHINESE} "åå´æ¡æ²é¢éå 0.1.0"
LangString PLUGIN_DESC_dev_turboism_plugin_boundingbox_warp_mirror ${LANG_SIMPCHINESE} "å¨åå´æ¡å å å±æ·»å éåæé®ï¼æå¼éåæ¹åå¯¹è¯æ¡å¹¶å¯¹æéæ²é¢åå½¢å¨æ§è¡åæ¬¡æ´ä½éåï¼å¯éä¿æå­ç©ä½å½¢ç¶ä¸åã"
LangString PLUGIN_NAME_dev_turboism_plugin_boundingbox_warp_mirror ${LANG_JAPANESE} "ãã¦ã³ãã£ã³ã°ããã¯ã¹ ã¯ã¼ããã©ã¼ 0.1.0"
LangString PLUGIN_DESC_dev_turboism_plugin_boundingbox_warp_mirror ${LANG_JAPANESE} "ãã¦ã³ãã£ã³ã°ããã¯ã¹ã®ãªã¼ãã¼ã¬ã¤ã«ãã©ã¼ãã¿ã³ãè¿½å ããæ¹åé¸æãã¤ã¢ã­ã°ããé¸æä¸­ã®ã¯ã¼ãããã©ã¼ãå¨ä½ãä¸æ¬ãã©ã¼ãªã³ã°ãã¾ãï¼å­ãªãã¸ã§ã¯ãã®å½¢ç¶ç¶­æã«å¯¾å¿ï¼ã"
LangString PLUGIN_NAME_dev_turboism_plugin_boundingbox_warp_mirror ${LANG_KOREAN} "ë°ì´ë© ë°ì¤ ìí ë¯¸ë¬ 0.1.0"
LangString PLUGIN_DESC_dev_turboism_plugin_boundingbox_warp_mirror ${LANG_KOREAN} "ë°ì´ë© ë°ì¤ ì¤ë²ë ì´ì ë¯¸ë¬ ë²í¼ì ì¶ê°íì¬ ë¯¸ë¬ ë°©í¥ ëíììë¥¼ ì´ê³ , ì íí ìí ëí¬ë¨¸ ì ì²´ì ì¼íì± ë¯¸ë¬ë¥¼ ì ì©í©ëë¤(ìì ì¤ë¸ì í¸ íí ì ì§ ì í ê°ë¥)."
LangString PLUGIN_NAME_dev_turboism_plugin_clipmask_viewer ${LANG_ENGLISH} "Clip Mask Viewer 0.1.0"
LangString PLUGIN_DESC_dev_turboism_plugin_clipmask_viewer ${LANG_ENGLISH} "Read-only clip-mask duplicate checker and visualizer: Turboism tab entry, graph/table inspector window, editor selection highlight, GUID copy."
LangString PLUGIN_NAME_dev_turboism_plugin_clipmask_viewer ${LANG_SIMPCHINESE} "åªè£èçæ¥çå¨ 0.1.0"
LangString PLUGIN_DESC_dev_turboism_plugin_clipmask_viewer ${LANG_SIMPCHINESE} "Turboism çåªè£èçæ¥çå¨ã"
LangString PLUGIN_NAME_dev_turboism_plugin_clipmask_viewer ${LANG_JAPANESE} "ã¯ãªãããã¹ã¯ãã¥ã¼ã¢ã¼ 0.1.0"
LangString PLUGIN_DESC_dev_turboism_plugin_clipmask_viewer ${LANG_JAPANESE} "Turboism ã®ã¯ãªãããã¹ã¯ãã¥ã¼ã¢ã¼ã"
LangString PLUGIN_NAME_dev_turboism_plugin_clipmask_viewer ${LANG_KOREAN} "í´ë¦½ ë§ì¤í¬ ë·°ì´ 0.1.0"
LangString PLUGIN_DESC_dev_turboism_plugin_clipmask_viewer ${LANG_KOREAN} "Turboism í´ë¦½ ë§ì¤í¬ ë·°ì´ íë¬ê·¸ì¸ìëë¤."
LangString PLUGIN_NAME_dev_turboism_plugin_command_palette ${LANG_ENGLISH} "Command Palette 0.1.0"
LangString PLUGIN_DESC_dev_turboism_plugin_command_palette ${LANG_ENGLISH} "Ctrl+K command launcher over the supported Editor commands."
LangString PLUGIN_NAME_dev_turboism_plugin_command_palette ${LANG_SIMPCHINESE} "å½ä»¤é¢æ¿ 0.1.0"
LangString PLUGIN_DESC_dev_turboism_plugin_command_palette ${LANG_SIMPCHINESE} "éè¿ Ctrl+K å¯å¨åæ¯æç Editor å½ä»¤çå¯å¨å¨ã"
LangString PLUGIN_NAME_dev_turboism_plugin_command_palette ${LANG_JAPANESE} "ã³ãã³ããã¬ãã 0.1.0"
LangString PLUGIN_DESC_dev_turboism_plugin_command_palette ${LANG_JAPANESE} "å¯¾å¿ãã Editor ã³ãã³ãã Ctrl+K ã§èµ·åããã©ã³ãã£ã¼ã"
LangString PLUGIN_NAME_dev_turboism_plugin_command_palette ${LANG_KOREAN} "ì»¤ë§¨ë íë í¸ 0.1.0"
LangString PLUGIN_DESC_dev_turboism_plugin_command_palette ${LANG_KOREAN} "ì§ìëë Editor ëªë ¹ì Ctrl+Kë¡ ì¤ííë ë°ì²ìëë¤."
LangString PLUGIN_NAME_dev_turboism_plugin_cubism_tab_filter ${LANG_ENGLISH} "Cubism Tab Filter 0.1.0"
LangString PLUGIN_DESC_dev_turboism_plugin_cubism_tab_filter ${LANG_ENGLISH} "Adds keyword filter boxes to the Parameter, Deformer, Scene and Log palette tabs."
LangString PLUGIN_NAME_dev_turboism_plugin_cubism_tab_filter ${LANG_SIMPCHINESE} "Cubism æ ç­¾ç­éå¨ 0.1.0"
LangString PLUGIN_DESC_dev_turboism_plugin_cubism_tab_filter ${LANG_SIMPCHINESE} "Turboism çCubism æ ç­¾ç­éå¨ã"
LangString PLUGIN_NAME_dev_turboism_plugin_cubism_tab_filter ${LANG_JAPANESE} "Cubism ã¿ããã£ã«ã¿ã¼ 0.1.0"
LangString PLUGIN_DESC_dev_turboism_plugin_cubism_tab_filter ${LANG_JAPANESE} "Turboism ã®Cubism ã¿ããã£ã«ã¿ã¼ã"
LangString PLUGIN_NAME_dev_turboism_plugin_cubism_tab_filter ${LANG_KOREAN} "Cubism í­ íí° 0.1.0"
LangString PLUGIN_DESC_dev_turboism_plugin_cubism_tab_filter ${LANG_KOREAN} "Turboism Cubism í­ íí° íë¬ê·¸ì¸ìëë¤."
LangString PLUGIN_NAME_dev_turboism_plugin_deformer_batch_rename ${LANG_ENGLISH} "Batch Rename Deformers 0.1.0"
LangString PLUGIN_DESC_dev_turboism_plugin_deformer_batch_rename ${LANG_ENGLISH} "Rename deformers from child names and bound parameters."
LangString PLUGIN_NAME_dev_turboism_plugin_deformer_batch_rename ${LANG_SIMPCHINESE} "åå½¢å¨æ¹ééå½å 0.1.0"
LangString PLUGIN_DESC_dev_turboism_plugin_deformer_batch_rename ${LANG_SIMPCHINESE} "æ ¹æ®å­ç©ä½åç§°åç»å®åæ°æ¹ééå½ååå½¢å¨ã"
LangString PLUGIN_NAME_dev_turboism_plugin_deformer_batch_rename ${LANG_JAPANESE} "ããã©ã¼ããä¸æ¬ãªãã¼ã  0.1.0"
LangString PLUGIN_DESC_dev_turboism_plugin_deformer_batch_rename ${LANG_JAPANESE} "å­ãªãã¸ã§ã¯ãåã¨é¢é£ãã©ã¡ã¼ã¿ããããã©ã¼ãã®ååãå¤æ´ãã¾ãã"
LangString PLUGIN_NAME_dev_turboism_plugin_deformer_batch_rename ${LANG_KOREAN} "ëí¬ë¨¸ ì´ë¦ ì¼ê´ ë³ê²½ 0.1.0"
LangString PLUGIN_DESC_dev_turboism_plugin_deformer_batch_rename ${LANG_KOREAN} "ìì ì´ë¦ê³¼ ì°ê²°ë íë¼ë¯¸í°ë¡ ëí¬ë¨¸ ì´ë¦ì ì¼ê´ ë³ê²½í©ëë¤ã"
LangString PLUGIN_NAME_dev_turboism_plugin_external_psd_edit ${LANG_ENGLISH} "External PSD Edit Plugin 0.1.0"
LangString PLUGIN_DESC_dev_turboism_plugin_external_psd_edit ${LANG_ENGLISH} "Edit the complete raw PSD of selected ArtMeshes in the configured or system default application; saves replace the raw image through the Cubism native importer."
LangString PLUGIN_NAME_dev_turboism_plugin_external_psd_edit ${LANG_SIMPCHINESE} "å¤é¨ PSD ç¼è¾æä»¶ 0.1.0"
LangString PLUGIN_DESC_dev_turboism_plugin_external_psd_edit ${LANG_SIMPCHINESE} "å¨èªå®ä¹æç³»ç»é»è®¤ç¨åºä¸­ç¼è¾æé ArtMesh çå®æ´åå¾ PSDï¼ä¿å­åéè¿ Cubism åçå¯¼å¥å¨æ¿æ¢åå¾ã"
LangString PLUGIN_NAME_dev_turboism_plugin_external_psd_edit ${LANG_JAPANESE} "å¤é¨PSDç·¨éãã©ã°ã¤ã³ 0.1.0"
LangString PLUGIN_DESC_dev_turboism_plugin_external_psd_edit ${LANG_JAPANESE} "é¸æããArtMeshã®å®å¨ãªåç»PSDãè¨­å®ããã¢ããªã¾ãã¯ã·ã¹ãã ã®æ¢å®ã®ã¢ããªã§ç·¨éãã¾ããä¿å­ããã¨Cubismãã¤ãã£ãã¤ã³ãã¼ã¿ã¼ã§åç»ãç½®ãæãããã¾ãã"
LangString PLUGIN_NAME_dev_turboism_plugin_external_psd_edit ${LANG_KOREAN} "ì¸ë¶ PSD í¸ì§ íë¬ê·¸ì¸ 0.1.0"
LangString PLUGIN_DESC_dev_turboism_plugin_external_psd_edit ${LANG_KOREAN} "ì íí ArtMeshì ì ì²´ ìë³¸ ì´ë¯¸ì§ PSDë¥¼ ì¤ì í ì± ëë ìì¤í ê¸°ë³¸ ì±ìì í¸ì§í©ëë¤. ì ì¥íë©´ Cubism ë¤ì´í°ë¸ ê°ì ¸ì¤ê¸°ë¡ ìë³¸ ì´ë¯¸ì§ê° êµì²´ë©ëë¤."
LangString PLUGIN_NAME_dev_turboism_plugin_frame_animation_import ${LANG_ENGLISH} "Frame Animation Import 0.1.0"
LangString PLUGIN_DESC_dev_turboism_plugin_frame_animation_import ${LANG_ENGLISH} "Import sequence frame animations from sprite sheets or image lists and bind to parameters with keyframe visibility."
LangString PLUGIN_NAME_dev_turboism_plugin_frame_animation_import ${LANG_SIMPCHINESE} "å¸§å¨ç»å¯¼å¥ 0.1.0"
LangString PLUGIN_DESC_dev_turboism_plugin_frame_animation_import ${LANG_SIMPCHINESE} "ä»ç²¾çµè¡¨åçæå¤å¼ å¾çå¯¼å¥åºåå¸§å¨ç»ï¼å¹¶èªå¨çæç»å®åæ°ä¸å³é®ç¹æ¾ç¤ºã"
LangString PLUGIN_NAME_dev_turboism_plugin_frame_animation_import ${LANG_JAPANESE} "ã³ãã¢ãã¡ã¼ã·ã§ã³ã¤ã³ãã¼ã 0.1.0"
LangString PLUGIN_DESC_dev_turboism_plugin_frame_animation_import ${LANG_JAPANESE} "ã¹ãã©ã¤ãã·ã¼ãã¾ãã¯é£çªç»åããã³ãã¢ãã¡ã¼ã·ã§ã³ãã¤ã³ãã¼ããããã©ã¡ã¼ã¿ã®ã­ã¼ãã©ã¼ã è¡¨ç¤ºãèªåè¨­å®ãã¾ãã"
LangString PLUGIN_NAME_dev_turboism_plugin_frame_animation_import ${LANG_KOREAN} "íë ì ì ëë©ì´ì ê°ì ¸ì¤ê¸° 0.1.0"
LangString PLUGIN_DESC_dev_turboism_plugin_frame_animation_import ${LANG_KOREAN} "ì¤íë¼ì´í¸ ìí¸ ëë ì°ë² ì´ë¯¸ì§ìì íë ì ì ëë©ì´ìì ê°ì ¸ì íë¼ë¯¸í° í¤í¼ íìë¥¼ ìëì¼ë¡ êµ¬ì±í©ëë¤."
LangString PLUGIN_NAME_dev_turboism_plugin_historypanel ${LANG_ENGLISH} "History Panel Plugin 0.1.0"
LangString PLUGIN_DESC_dev_turboism_plugin_historypanel ${LANG_ENGLISH} "Photoshop-style history pane with native Undo history and snapshot-bound Undo/Redo navigation."
LangString PLUGIN_NAME_dev_turboism_plugin_historypanel ${LANG_SIMPCHINESE} "åå²è®°å½é¢æ¿æä»¶ 0.1.0"
LangString PLUGIN_DESC_dev_turboism_plugin_historypanel ${LANG_SIMPCHINESE} "Photoshop é£æ ¼åå²é¢æ¿ï¼æ¾ç¤ºåçæ¤éåå²å¹¶æä¾ç»å®å¿«ç§çæ¤é/éåå¯¼èªã"
LangString PLUGIN_NAME_dev_turboism_plugin_historypanel ${LANG_JAPANESE} "å±¥æ­´ããã«ãã©ã°ã¤ã³ 0.1.0"
LangString PLUGIN_DESC_dev_turboism_plugin_historypanel ${LANG_JAPANESE} "ãã¤ãã£ã Undo å±¥æ­´ã¨ã¹ãããã·ã§ããã«æç¸ããã Undo/Redo ããã²ã¼ã·ã§ã³ãæä¾ãã Photoshop é¢¨å±¥æ­´ããã«ã§ãã"
LangString PLUGIN_NAME_dev_turboism_plugin_historypanel ${LANG_KOREAN} "ê¸°ë¡ í¨ë íë¬ê·¸ì¸ 0.1.0"
LangString PLUGIN_DESC_dev_turboism_plugin_historypanel ${LANG_KOREAN} "ê¸°ë³¸ ì¤í ì·¨ì ê¸°ë¡ê³¼ ì¤ëì· ê¸°ë° ì¤í ì·¨ì/ë¤ì ì¤í íìì ì ê³µíë Photoshop ì¤íì¼ ê¸°ë¡ í¨ëìëë¤."
LangString PLUGIN_NAME_dev_turboism_plugin_mcp ${LANG_ENGLISH} "Turboism MCP Server 0.1.0"
LangString PLUGIN_DESC_dev_turboism_plugin_mcp ${LANG_ENGLISH} "Loopback MCP server for typed Cubism inspection, editing, history, and Editor-command workflows."
LangString PLUGIN_NAME_dev_turboism_plugin_mcp ${LANG_SIMPCHINESE} "Turboism MCP æå¡å¨ 0.1.0"
LangString PLUGIN_DESC_dev_turboism_plugin_mcp ${LANG_SIMPCHINESE} "éè¿ç±»åå MCP å·¥ä½æµå¬å¼æ¨¡åãåæ°ãåå²è®°å½åç¼è¾å¨å½ä»¤çç¯åæå¡å¨ã"
LangString PLUGIN_NAME_dev_turboism_plugin_mcp ${LANG_JAPANESE} "Turboism MCP ãµã¼ãã¼ 0.1.0"
LangString PLUGIN_DESC_dev_turboism_plugin_mcp ${LANG_JAPANESE} "ã¢ãã«ããã©ã¡ã¼ã¿ãå±¥æ­´ãã¨ãã£ã¿ã¼ã³ãã³ããåä»ã MCP ã¯ã¼ã¯ãã­ã¼ã¨ãã¦å¬éããã«ã¼ãããã¯ãµã¼ãã¼ã"
LangString PLUGIN_NAME_dev_turboism_plugin_mcp ${LANG_KOREAN} "Turboism MCP ìë² 0.1.0"
LangString PLUGIN_DESC_dev_turboism_plugin_mcp ${LANG_KOREAN} "ëª¨ë¸, íë¼ë¯¸í°, ê¸°ë¡ ë° ìëí° ëªë ¹ì íìíë MCP ìí¬íë¡ë¡ ì ê³µíë ë£¨íë°± ìë²ìëë¤."
LangString PLUGIN_NAME_dev_turboism_plugin_mesh_edit_mirror_axis_enhance ${LANG_ENGLISH} "Mesh Inspector and Mirror-Axis Tools 0.1.0"
LangString PLUGIN_DESC_dev_turboism_plugin_mesh_edit_mirror_axis_enhance ${LANG_ENGLISH} "Inspects mesh/deformer state and contributes the bounded mirror-axis rotation control for verified Cubism hosts."
LangString PLUGIN_NAME_dev_turboism_plugin_mesh_edit_mirror_axis_enhance ${LANG_SIMPCHINESE} "ç½æ ¼æ£æ¥ä¸éåè½´å·¥å· 0.1.0"
LangString PLUGIN_DESC_dev_turboism_plugin_mesh_edit_mirror_axis_enhance ${LANG_SIMPCHINESE} "Turboism çç½æ ¼æ£æ¥ä¸éåè½´å·¥å·ã"
LangString PLUGIN_NAME_dev_turboism_plugin_mesh_edit_mirror_axis_enhance ${LANG_JAPANESE} "ã¡ãã·ã¥æ¤æ»ã¨ãã©ã¼è»¸ãã¼ã« 0.1.0"
LangString PLUGIN_DESC_dev_turboism_plugin_mesh_edit_mirror_axis_enhance ${LANG_JAPANESE} "Turboism ã®ã¡ãã·ã¥æ¤æ»ã¨ãã©ã¼è»¸ãã¼ã«ã"
LangString PLUGIN_NAME_dev_turboism_plugin_mesh_edit_mirror_axis_enhance ${LANG_KOREAN} "ë©ì ê²ì¬ ë° ë¯¸ë¬ ì¶ ëêµ¬ 0.1.0"
LangString PLUGIN_DESC_dev_turboism_plugin_mesh_edit_mirror_axis_enhance ${LANG_KOREAN} "Turboism ë©ì ê²ì¬ ë° ë¯¸ë¬ ì¶ ëêµ¬ íë¬ê·¸ì¸ìëë¤."
LangString PLUGIN_NAME_dev_turboism_plugin_model_import_3d ${LANG_ENGLISH} "3D Model Import 0.1.0"
LangString PLUGIN_DESC_dev_turboism_plugin_model_import_3d ${LANG_ENGLISH} "Imports GLB characters as native Cubism meshes with material textures and XYZ rotation. Includes a diagnostic 3D viewer and static glTF, OBJ/MTL and ZIP import."
LangString PLUGIN_NAME_dev_turboism_plugin_model_import_3d ${LANG_SIMPCHINESE} "3D æ¨¡åå¯¼å¥ 0.1.0"
LangString PLUGIN_DESC_dev_turboism_plugin_model_import_3d ${LANG_SIMPCHINESE} "å° GLB äººç©å¯¼å¥ä¸º Cubism åçç½æ ¼ï¼æ¯ææè´¨è´´å¾å XYZ æè½¬ãå¦æä¾è¯æ­æ¥çå¨åéæ glTFãOBJ/MTLãZIP å¯¼å¥ã"
LangString PLUGIN_NAME_dev_turboism_plugin_model_import_3d ${LANG_JAPANESE} "3D ã¢ãã«ã®ã¤ã³ãã¼ã 0.1.0"
LangString PLUGIN_DESC_dev_turboism_plugin_model_import_3d ${LANG_JAPANESE} "GLB ã­ã£ã©ã¯ã¿ã¼ãæè³ªãã¯ã¹ãã£ã¨ XYZ åè»¢ä»ãã® Cubism ãã¤ãã£ãã¡ãã·ã¥ã¨ãã¦èª­ã¿è¾¼ã¿ã¾ããè¨ºæ­ãã¥ã¼ã¢ã¼ã¨éç glTFãOBJ/MTLãZIP ã®èª­ã¿è¾¼ã¿ãæä¾ãã¾ãã"
LangString PLUGIN_NAME_dev_turboism_plugin_model_import_3d ${LANG_KOREAN} "3D ëª¨ë¸ ê°ì ¸ì¤ê¸° 0.1.0"
LangString PLUGIN_DESC_dev_turboism_plugin_model_import_3d ${LANG_KOREAN} "GLB ìºë¦­í°ë¥¼ ì¬ì§ íì¤ì²ì XYZ íì ì ê°ì¶ Cubism ë¤ì´í°ë¸ ë©ìë¡ ê°ì ¸ìµëë¤. ì§ë¨ ë·°ì´ì ì ì  glTF, OBJ/MTL, ZIP ê°ì ¸ì¤ê¸°ë ì ê³µí©ëë¤."
LangString PLUGIN_NAME_dev_turboism_plugin_palette_label_style ${LANG_ENGLISH} "Palette Label Style Plugin 0.1.0"
LangString PLUGIN_DESC_dev_turboism_plugin_palette_label_style ${LANG_ENGLISH} "Label text and background colors for Deformer, Part, and Parameter palette entries via context menus."
LangString PLUGIN_NAME_dev_turboism_plugin_palette_label_style ${LANG_SIMPCHINESE} "é¢æ¿æ ç­¾æ ·å¼æä»¶ 0.1.0"
LangString PLUGIN_DESC_dev_turboism_plugin_palette_label_style ${LANG_SIMPCHINESE} "éè¿å³é®èåä¸ºåå½¢å¨ãé¨ä»¶ååæ°é¢æ¿æ¡ç®è®¾ç½®ææ¬ä¸èæ¯é¢è²ã"
LangString PLUGIN_NAME_dev_turboism_plugin_palette_label_style ${LANG_JAPANESE} "ãã¬ããã©ãã«ã¹ã¿ã¤ã«ãã©ã°ã¤ã³ 0.1.0"
LangString PLUGIN_DESC_dev_turboism_plugin_palette_label_style ${LANG_JAPANESE} "ããã©ã¼ãããã¼ãããã©ã¡ã¼ã¿ãã¬ããã®åé ç®ã«ã³ã³ãã­ã¹ãã¡ãã¥ã¼ãããã­ã¹ãè²ã¨èæ¯è²ãè¨­å®ãã¾ãã"
LangString PLUGIN_NAME_dev_turboism_plugin_palette_label_style ${LANG_KOREAN} "íë í¸ ë ì´ë¸ ì¤íì¼ íë¬ê·¸ì¸ 0.1.0"
LangString PLUGIN_DESC_dev_turboism_plugin_palette_label_style ${LANG_KOREAN} "Turboism íë í¸ ë ì´ë¸ ì¤íì¼ íë¬ê·¸ì¸ìëë¤."
LangString PLUGIN_NAME_dev_turboism_plugin_parameter_batch_transfer ${LANG_ENGLISH} "Parameter Batch Transfer 0.1.0"
LangString PLUGIN_DESC_dev_turboism_plugin_parameter_batch_transfer ${LANG_ENGLISH} "Batch-transfer parameter bindings of one ArtMesh or Deformer to other parameters, with optional inversion, through a modal dialog."
LangString PLUGIN_NAME_dev_turboism_plugin_parameter_batch_transfer ${LANG_SIMPCHINESE} "åæ°æ¹éä¼ è¾ 0.1.0"
LangString PLUGIN_DESC_dev_turboism_plugin_parameter_batch_transfer ${LANG_SIMPCHINESE} "Turboism çåæ°æ¹éä¼ è¾ã"
LangString PLUGIN_NAME_dev_turboism_plugin_parameter_batch_transfer ${LANG_JAPANESE} "ãã©ã¡ã¼ã¿ä¸æ¬è»¢é 0.1.0"
LangString PLUGIN_DESC_dev_turboism_plugin_parameter_batch_transfer ${LANG_JAPANESE} "Turboism ã®ãã©ã¡ã¼ã¿ä¸æ¬è»¢éã"
LangString PLUGIN_NAME_dev_turboism_plugin_parameter_batch_transfer ${LANG_KOREAN} "íë¼ë¯¸í° ì¼ê´ ì ì¡ 0.1.0"
LangString PLUGIN_DESC_dev_turboism_plugin_parameter_batch_transfer ${LANG_KOREAN} "Turboism íë¼ë¯¸í° ì¼ê´ ì ì¡ íë¬ê·¸ì¸ìëë¤."
LangString PLUGIN_NAME_dev_turboism_plugin_parameter_sync ${LANG_ENGLISH} "Parameter Sync Plugin 0.1.0"
LangString PLUGIN_DESC_dev_turboism_plugin_parameter_sync ${LANG_ENGLISH} "Synchronize parameter values in real time with normalized range mapping across checked parameters."
LangString PLUGIN_NAME_dev_turboism_plugin_parameter_sync ${LANG_SIMPCHINESE} "åæ°åæ­¥æä»¶ 0.1.0"
LangString PLUGIN_DESC_dev_turboism_plugin_parameter_sync ${LANG_SIMPCHINESE} "åºäºå½ä¸åç¾åæ¯è¿åº¦çåæ°æ°å¼å®æ¶åæ­¥èå¨æä»¶ã"
LangString PLUGIN_NAME_dev_turboism_plugin_parameter_sync ${LANG_JAPANESE} "ãã©ã¡ã¼ã¿åæãã©ã°ã¤ã³ 0.1.0"
LangString PLUGIN_DESC_dev_turboism_plugin_parameter_sync ${LANG_JAPANESE} "æ­£è¦åç¯å²ãããã³ã°ã«åºã¥ããã©ã¡ã¼ã¿å¤ã®ãªã¢ã«ã¿ã¤ã åæãã©ã°ã¤ã³ã"
LangString PLUGIN_NAME_dev_turboism_plugin_parameter_sync ${LANG_KOREAN} "ë§¤ê°ë³ì ëê¸°í íë¬ê·¸ì¸ 0.1.0"
LangString PLUGIN_DESC_dev_turboism_plugin_parameter_sync ${LANG_KOREAN} "ì ê·í ë²ì ë§¤íì ì§ìíë ë§¤ê°ë³ì ê° ì¤ìê° ëê¸°í íë¬ê·¸ì¸."
LangString PLUGIN_NAME_dev_turboism_plugin_perf_stats ${LANG_ENGLISH} "Performance Statistics 0.1.0"
LangString PLUGIN_DESC_dev_turboism_plugin_perf_stats ${LANG_ENGLISH} "Real-time Cubism process performance charts: CPU, FPS, and JVM memory as an embedded panel and a standalone window."
LangString PLUGIN_NAME_dev_turboism_plugin_perf_stats ${LANG_SIMPCHINESE} "æ§è½ç»è®¡ 0.1.0"
LangString PLUGIN_DESC_dev_turboism_plugin_perf_stats ${LANG_SIMPCHINESE} "Turboism çæ§è½ç»è®¡ã"
LangString PLUGIN_NAME_dev_turboism_plugin_perf_stats ${LANG_JAPANESE} "ããã©ã¼ãã³ã¹çµ±è¨ 0.1.0"
LangString PLUGIN_DESC_dev_turboism_plugin_perf_stats ${LANG_JAPANESE} "Turboism ã®ããã©ã¼ãã³ã¹çµ±è¨ã"
LangString PLUGIN_NAME_dev_turboism_plugin_perf_stats ${LANG_KOREAN} "ì±ë¥ íµê³ 0.1.0"
LangString PLUGIN_DESC_dev_turboism_plugin_perf_stats ${LANG_KOREAN} "Turboism ì±ë¥ íµê³ íë¬ê·¸ì¸ìëë¤."
LangString PLUGIN_NAME_dev_turboism_plugin_physics_editor ${LANG_ENGLISH} "Physics Settings Enhancer 0.1.0"
LangString PLUGIN_DESC_dev_turboism_plugin_physics_editor ${LANG_ENGLISH} "Adds Physics Settings group select-all and reopen retention."
LangString PLUGIN_NAME_dev_turboism_plugin_physics_editor ${LANG_SIMPCHINESE} "ç©çè¿ç®è®¾ç½®å¢å¼º 0.1.0"
LangString PLUGIN_DESC_dev_turboism_plugin_physics_editor ${LANG_SIMPCHINESE} "ä¸º Cubism ç©çè¿ç®è®¾ç½®ç»åè¡¨æ°å¢è¡¨å¤´å¨éä¸éå¼ç¶æä¿çã"
LangString PLUGIN_NAME_dev_turboism_plugin_physics_editor ${LANG_JAPANESE} "ç©çæ¼ç®è¨­å®æ¡å¼µ 0.1.0"
LangString PLUGIN_DESC_dev_turboism_plugin_physics_editor ${LANG_JAPANESE} "Cubism ã®ç©çæ¼ç®è¨­å®ã°ã«ã¼ããªã¹ãã«è¡¨é ­ãã¹ã¦é¸æã¨åè¡¨ç¤ºæã®ç¶æä¿æãè¿½å ãã¾ãã"
LangString PLUGIN_NAME_dev_turboism_plugin_physics_editor ${LANG_KOREAN} "ë¬¼ë¦¬ ì¤ì  í¥ìê¸° 0.1.0"
LangString PLUGIN_DESC_dev_turboism_plugin_physics_editor ${LANG_KOREAN} "Cubism ë¬¼ë¦¬ ì¤ì  ê·¸ë£¹ ëª©ë¡ì í¤ë ì ì²´ ì í ë° ì°½ ë¤ì ì´ê¸° ì ìí ì ì§ë¥¼ ì¶ê°í©ëë¤."
LangString PLUGIN_NAME_dev_turboism_plugin_protected_export ${LANG_ENGLISH} "Protected Export 0.1.0"
LangString PLUGIN_DESC_dev_turboism_plugin_protected_export ${LANG_ENGLISH} "Optional protected runtime-model export: works on a task-owned copy, flattens Warp/Rotation deformers, obfuscates ArtMesh identities, validates the complete staged output, then publishes atomically. Admitted only on reviewed Cubism versions and eligible models."
LangString PLUGIN_NAME_dev_turboism_plugin_protected_export ${LANG_SIMPCHINESE} "åä¿æ¤å¯¼åº 0.1.0"
LangString PLUGIN_DESC_dev_turboism_plugin_protected_export ${LANG_SIMPCHINESE} "å¯éçåä¿æ¤è¿è¡æ¶æ¨¡åå¯¼åºï¼å¨ä»»å¡ä¸ç¨å¯æ¬ä¸æå¹³å Warp/Rotation åå½¢å¨ãæ··æ· ArtMesh æ è¯ãéªè¯å®æ´æå­è¾åºååå­åå¸ï¼ä»å¯¹å®¡æ¥éè¿ç Cubism çæ¬ä¸æ»¡è¶³åå¥æ£æ¥çæ¨¡åçæã"
LangString PLUGIN_NAME_dev_turboism_plugin_protected_export ${LANG_JAPANESE} "ä¿è­·ä»ãã¨ã¯ã¹ãã¼ã 0.1.0"
LangString PLUGIN_DESC_dev_turboism_plugin_protected_export ${LANG_JAPANESE} "ãªãã·ã§ã³ã®ä¿è­·ä»ãã©ã³ã¿ã¤ã ã¢ãã«ã¨ã¯ã¹ãã¼ãã§ããã¿ã¹ã¯å°ç¨ã³ãã¼ä¸ã§Warp/åè»¢ããã©ã¼ããå¹³å¦åããArtMeshè­å¥å­ãé£èª­åããå®å¨ãªã¹ãã¼ã¸ã³ã°åºåãæ¤è¨¼ãã¦ããã¢ãããã¯ã«å¬éãã¾ããã¬ãã¥ã¼æ¸ã¿ã®Cubismãã¼ã¸ã§ã³ã¨é©æ ¼ãªã¢ãã«ã§ã®ã¿æå¹ã§ãã"
LangString PLUGIN_NAME_dev_turboism_plugin_protected_export ${LANG_KOREAN} "ë³´í¸ë ë´ë³´ë´ê¸° 0.1.0"
LangString PLUGIN_DESC_dev_turboism_plugin_protected_export ${LANG_KOREAN} "ì íì  ë³´í¸ ë°íì ëª¨ë¸ ë´ë³´ë´ê¸°ìëë¤. ìì ì ì© ë³µì¬ë³¸ìì Warp/íì  ëí¬ë¨¸ë¥¼ ííííê³  ArtMesh ìë³ìë¥¼ ëëííë©°, ìì±ë ì¤íì´ì§ ì¶ë ¥ì ê²ì¦í ë¤ ììì ì¼ë¡ ê²ìí©ëë¤. ê²í ë Cubism ë²ì ê³¼ ì ê²© ëª¨ë¸ììë§ ëìí©ëë¤."
LangString PLUGIN_NAME_dev_turboism_plugin_psd_clip_mask_import ${LANG_ENGLISH} "PSD Clip Mask Import Plugin 0.1.0"
LangString PLUGIN_DESC_dev_turboism_plugin_psd_clip_mask_import ${LANG_ENGLISH} "Imports ordered PSD clipping relationships into ArtMesh clip-mask assignments with an explicit overwrite policy."
LangString PLUGIN_NAME_dev_turboism_plugin_psd_clip_mask_import ${LANG_SIMPCHINESE} "PSD åªè´´èçå¯¼å¥æä»¶ 0.1.0"
LangString PLUGIN_DESC_dev_turboism_plugin_psd_clip_mask_import ${LANG_SIMPCHINESE} "æé¡ºåºå° PSD åªè´´å³ç³»å¯¼å¥ ArtMesh åªè´´èçåéï¼å¹¶æä¾æç¡®çè¦çç­ç¥ã"
LangString PLUGIN_NAME_dev_turboism_plugin_psd_clip_mask_import ${LANG_JAPANESE} "PSD ã¯ãªããã³ã°ãã¹ã¯ã¤ã³ãã¼ããã©ã°ã¤ã³ 0.1.0"
LangString PLUGIN_DESC_dev_turboism_plugin_psd_clip_mask_import ${LANG_JAPANESE} "PSD ã®ã¯ãªããã³ã°é¢ä¿ãé åºã©ãã ArtMesh ã®ã¯ãªããã³ã°ãã¹ã¯å²ãå½ã¦ã¸åãè¾¼ã¿ãæç¤ºçãªä¸æ¸ãæ¹éãæä¾ãã¾ãã"
LangString PLUGIN_NAME_dev_turboism_plugin_psd_clip_mask_import ${LANG_KOREAN} "PSD í´ë¦½ ë§ì¤í¬ ê°ì ¸ì¤ê¸° íë¬ê·¸ì¸ 0.1.0"
LangString PLUGIN_DESC_dev_turboism_plugin_psd_clip_mask_import ${LANG_KOREAN} "PSD í´ë¦¬í ê´ê³ë¥¼ ììëë¡ ArtMesh í´ë¦½ ë§ì¤í¬ í ë¹ì¼ë¡ ê°ì ¸ì¤ë©° ëªìì ì¸ ë®ì´ì°ê¸° ì ì±ì ì ê³µí©ëë¤."
LangString PLUGIN_NAME_dev_turboism_plugin_recent_preview ${LANG_ENGLISH} "Recent Preview 0.1.0"
LangString PLUGIN_DESC_dev_turboism_plugin_recent_preview ${LANG_ENGLISH} "Captures bounded preview thumbnails for recent project files and contributes them to the Recent Files hover popup."
LangString PLUGIN_NAME_dev_turboism_plugin_recent_preview ${LANG_SIMPCHINESE} "æè¿é¢è§ 0.1.0"
LangString PLUGIN_DESC_dev_turboism_plugin_recent_preview ${LANG_SIMPCHINESE} "Turboism çæè¿é¢è§ã"
LangString PLUGIN_NAME_dev_turboism_plugin_recent_preview ${LANG_JAPANESE} "æè¿ã®ãã¬ãã¥ã¼ 0.1.0"
LangString PLUGIN_DESC_dev_turboism_plugin_recent_preview ${LANG_JAPANESE} "Turboism ã®æè¿ã®ãã¬ãã¥ã¼ã"
LangString PLUGIN_NAME_dev_turboism_plugin_recent_preview ${LANG_KOREAN} "ìµê·¼ ë¯¸ë¦¬ë³´ê¸° 0.1.0"
LangString PLUGIN_DESC_dev_turboism_plugin_recent_preview ${LANG_KOREAN} "Turboism ìµê·¼ ë¯¸ë¦¬ë³´ê¸° íë¬ê·¸ì¸ìëë¤."
LangString PLUGIN_NAME_dev_turboism_plugin_scene_palette_enhancer ${LANG_ENGLISH} "Scene Palette Enhancer 0.1.0"
LangString PLUGIN_DESC_dev_turboism_plugin_scene_palette_enhancer ${LANG_ENGLISH} "Sorts and manually reorders items in the Cubism Scene palette."
LangString PLUGIN_NAME_dev_turboism_plugin_scene_palette_enhancer ${LANG_SIMPCHINESE} "åºæ¯é¢æ¿å¢å¼ºå¨ 0.1.0"
LangString PLUGIN_DESC_dev_turboism_plugin_scene_palette_enhancer ${LANG_SIMPCHINESE} "å¯¹ Cubism åºæ¯é¢æ¿ä¸­çé¡¹ç®è¿è¡æåºä¸æå¨éæ°æåºã"
LangString PLUGIN_NAME_dev_turboism_plugin_scene_palette_enhancer ${LANG_JAPANESE} "ã·ã¼ã³ãã¬ããæ¡å¼µ 0.1.0"
LangString PLUGIN_DESC_dev_turboism_plugin_scene_palette_enhancer ${LANG_JAPANESE} "Cubism ã·ã¼ã³ãã¬ããã®é ç®ãã½ã¼ãããã³æåã§ä¸¦ã¹æ¿ãã¾ãã"
LangString PLUGIN_NAME_dev_turboism_plugin_scene_palette_enhancer ${LANG_KOREAN} "ì¥ë©´ íë í¸ í¥ìê¸° 0.1.0"
LangString PLUGIN_DESC_dev_turboism_plugin_scene_palette_enhancer ${LANG_KOREAN} "Turboism ì¥ë©´ íë í¸ í¥ìê¸° íë¬ê·¸ì¸ìëë¤."
LangString PLUGIN_NAME_dev_turboism_plugin_selection_brush ${LANG_ENGLISH} "Selection Brush 0.1.0"
LangString PLUGIN_DESC_dev_turboism_plugin_selection_brush ${LANG_ENGLISH} "Select ArtMesh vertices and Warp Deformer control points with an adjustable-radius brush."
LangString PLUGIN_NAME_dev_turboism_plugin_selection_brush ${LANG_SIMPCHINESE} "éåºç¬å· 0.1.0"
LangString PLUGIN_DESC_dev_turboism_plugin_selection_brush ${LANG_SIMPCHINESE} "ä½¿ç¨å¯è°åå¾çç¬å·éæ© ArtMesh é¡¶ç¹åæ²é¢åå½¢å¨æ§å¶ç¹ã"
LangString PLUGIN_NAME_dev_turboism_plugin_selection_brush ${LANG_JAPANESE} "é¸æãã©ã· 0.1.0"
LangString PLUGIN_DESC_dev_turboism_plugin_selection_brush ${LANG_JAPANESE} "åå¾ãèª¿æ´ã§ãããã©ã·ã§ ArtMesh ã®é ç¹ã¨ã¯ã¼ãããã©ã¼ãã®å¶å¾¡ç¹ãé¸æãã¾ãã"
LangString PLUGIN_NAME_dev_turboism_plugin_selection_brush ${LANG_KOREAN} "ì í ë¸ë¬ì 0.1.0"
LangString PLUGIN_DESC_dev_turboism_plugin_selection_brush ${LANG_KOREAN} "ë°ê²½ì ì¡°ì í  ì ìë ë¸ë¬ìë¡ ArtMesh ì ì ê³¼ ìí ëí¬ë¨¸ì ì ì´ì ì ì íí©ëë¤."
LangString PLUGIN_NAME_dev_turboism_plugin_texture_atlas_stats ${LANG_ENGLISH} "Texture Atlas Statistics 0.1.0"
LangString PLUGIN_DESC_dev_turboism_plugin_texture_atlas_stats ${LANG_ENGLISH} "Shows the total and current-texture model-image counts in the native texture-atlas editor window."
LangString PLUGIN_NAME_dev_turboism_plugin_texture_atlas_stats ${LANG_SIMPCHINESE} "çº¹çå¾éç»è®¡ 0.1.0"
LangString PLUGIN_DESC_dev_turboism_plugin_texture_atlas_stats ${LANG_SIMPCHINESE} "Turboism ççº¹çå¾éç»è®¡ã"
LangString PLUGIN_NAME_dev_turboism_plugin_texture_atlas_stats ${LANG_JAPANESE} "ãã¯ã¹ãã£ã¢ãã©ã¹çµ±è¨ 0.1.0"
LangString PLUGIN_DESC_dev_turboism_plugin_texture_atlas_stats ${LANG_JAPANESE} "Turboism ã®ãã¯ã¹ãã£ã¢ãã©ã¹çµ±è¨ã"
LangString PLUGIN_NAME_dev_turboism_plugin_texture_atlas_stats ${LANG_KOREAN} "íì¤ì² ìíë¼ì¤ íµê³ 0.1.0"
LangString PLUGIN_DESC_dev_turboism_plugin_texture_atlas_stats ${LANG_KOREAN} "Turboism íì¤ì² ìíë¼ì¤ íµê³ íë¬ê·¸ì¸ìëë¤."
LangString PLUGIN_NAME_dev_turboism_plugin_uitheme ${LANG_ENGLISH} "UI Theme Plugin 0.1.0"
LangString PLUGIN_DESC_dev_turboism_plugin_uitheme ${LANG_ENGLISH} "Legacy-compatible theme packages, built-in themes, theme manager workflow, and exact-version Cubism appearance application."
LangString PLUGIN_NAME_dev_turboism_plugin_uitheme ${LANG_SIMPCHINESE} "UI ä¸»é¢æä»¶ 0.1.0"
LangString PLUGIN_DESC_dev_turboism_plugin_uitheme ${LANG_SIMPCHINESE} "TurboismçUI ä¸»é¢æä»¶ã"
LangString PLUGIN_NAME_dev_turboism_plugin_uitheme ${LANG_JAPANESE} "UI ãã¼ããã©ã°ã¤ã³ 0.1.0"
LangString PLUGIN_DESC_dev_turboism_plugin_uitheme ${LANG_JAPANESE} "Turboismã®UI ãã¼ããã©ã°ã¤ã³ã"
LangString PLUGIN_NAME_dev_turboism_plugin_uitheme ${LANG_KOREAN} "UI íë§ íë¬ê·¸ì¸ 0.1.0"
LangString PLUGIN_DESC_dev_turboism_plugin_uitheme ${LANG_KOREAN} "Turboism UI íë§ íë¬ê·¸ì¸ìëë¤."
LangString PLUGIN_NAME_dev_turboism_plugin_warp_deformer_alt_symmetry ${LANG_ENGLISH} "Warp Deformer Alt Symmetry 0.1.0"
LangString PLUGIN_DESC_dev_turboism_plugin_warp_deformer_alt_symmetry ${LANG_ENGLISH} "Button-controlled mirror editing for Warp Deformer control points."
LangString PLUGIN_NAME_dev_turboism_plugin_warp_deformer_alt_symmetry ${LANG_SIMPCHINESE} "æ²é¢åå½¢å¨ Alt å¯¹ç§° 0.1.0"
LangString PLUGIN_DESC_dev_turboism_plugin_warp_deformer_alt_symmetry ${LANG_SIMPCHINESE} "éè¿ç»å¸æé®åæ¢æ²é¢åå½¢å¨æ§å¶ç¹çéåç¼è¾ã"
LangString PLUGIN_NAME_dev_turboism_plugin_warp_deformer_alt_symmetry ${LANG_JAPANESE} "ã¯ã¼ãããã©ã¼ã Alt å¯¾ç§° 0.1.0"
LangString PLUGIN_DESC_dev_turboism_plugin_warp_deformer_alt_symmetry ${LANG_JAPANESE} "ã­ã£ã³ãã¹ã®ãã¿ã³ã§ã¯ã¼ãããã©ã¼ãå¶å¾¡ç¹ã®ãã©ã¼ç·¨éãåãæ¿ãã¾ãã"
LangString PLUGIN_NAME_dev_turboism_plugin_warp_deformer_alt_symmetry ${LANG_KOREAN} "ìí ëí¬ë¨¸ Alt ëì¹­ 0.1.0"
LangString PLUGIN_DESC_dev_turboism_plugin_warp_deformer_alt_symmetry ${LANG_KOREAN} "ìºë²ì¤ ë²í¼ì¼ë¡ ìí ëí¬ë¨¸ì ìí¸ë©ìì ë¯¸ë¬ í¸ì§ì ì íí©ëë¤. ìí¸ë©ì ëì¹­ì¶ì ì´ëíê³  íì í  ì ììµëë¤."
LangString PLUGIN_NAME_dev_turboism_plugin_webdav ${LANG_ENGLISH} "WebDAV Auto-Backup Sync Plugin 0.1.0"
LangString PLUGIN_DESC_dev_turboism_plugin_webdav ${LANG_ENGLISH} "Uploads Cubism auto-backup artifacts to a WebDAV endpoint (JDK HttpClient only)."
LangString PLUGIN_NAME_dev_turboism_plugin_webdav ${LANG_SIMPCHINESE} "WebDAV èªå¨å¤ä»½åæ­¥æä»¶ 0.1.0"
LangString PLUGIN_DESC_dev_turboism_plugin_webdav ${LANG_SIMPCHINESE} "TurboismçWebDAV èªå¨å¤ä»½åæ­¥æä»¶ã"
LangString PLUGIN_NAME_dev_turboism_plugin_webdav ${LANG_JAPANESE} "WebDAV èªåããã¯ã¢ããåæãã©ã°ã¤ã³ 0.1.0"
LangString PLUGIN_DESC_dev_turboism_plugin_webdav ${LANG_JAPANESE} "Turboismã®WebDAV èªåããã¯ã¢ããåæãã©ã°ã¤ã³ã"
LangString PLUGIN_NAME_dev_turboism_plugin_webdav ${LANG_KOREAN} "WebDAV ìë ë°±ì ëê¸°í íë¬ê·¸ì¸ 0.1.0"
LangString PLUGIN_DESC_dev_turboism_plugin_webdav ${LANG_KOREAN} "Turboism WebDAV ìë ë°±ì ëê¸°í íë¬ê·¸ì¸ìëë¤."

; 导出完整捆绑插件 id 清单，供前置配置提交保留无关禁用项。
Function SetBundledPluginIds
  StrCpy $bundledPluginIds "dev.turboism.plugin.acp;dev.turboism.plugin.artmesh-mirror;dev.turboism.plugin.texture-atlas-nesting;dev.turboism.plugin.texture-atlas-dalsoo;dev.turboism.plugin.texture-atlas-folders;dev.turboism.plugin.texture-atlas;dev.turboism.plugin.boundingbox-warp-mirror;dev.turboism.plugin.clipmask-viewer;dev.turboism.plugin.command-palette;dev.turboism.plugin.cubism-tab-filter;dev.turboism.plugin.deformer-batch-rename;dev.turboism.plugin.external-psd-edit;dev.turboism.plugin.frame-animation-import;dev.turboism.plugin.historypanel;dev.turboism.plugin.mcp;dev.turboism.plugin.mesh-edit-mirror-axis-enhance;dev.turboism.plugin.model-import-3d;dev.turboism.plugin.palette-label-style;dev.turboism.plugin.parameter-batch-transfer;dev.turboism.plugin.parameter-sync;dev.turboism.plugin.perf-stats;dev.turboism.plugin.physics-editor;dev.turboism.plugin.protected-export;dev.turboism.plugin.psd-clip-mask-import;dev.turboism.plugin.recent-preview;dev.turboism.plugin.scene-palette-enhancer;dev.turboism.plugin.selection-brush;dev.turboism.plugin.texture-atlas-stats;dev.turboism.plugin.uitheme;dev.turboism.plugin.warp-deformer-alt-symmetry;dev.turboism.plugin.webdav"
FunctionEnd

; 卸载白名单：仅删除安装器部署的插件 JAR；未知文件与第三方 JAR 保留。
Function un.DeleteInstallerPluginJars
  Delete "$INSTDIR\plugins\acp.jar"
  Delete "$INSTDIR\plugins\artmesh-mirror.jar"
  Delete "$INSTDIR\plugins\atlas-bitmask-nesting.jar"
  Delete "$INSTDIR\plugins\atlas-dalsoo-polygon.jar"
  Delete "$INSTDIR\plugins\atlas-folder-layout.jar"
  Delete "$INSTDIR\plugins\atlas-maxrects-bssf.jar"
  Delete "$INSTDIR\plugins\boundingbox-warp-mirror.jar"
  Delete "$INSTDIR\plugins\clipmask-viewer.jar"
  Delete "$INSTDIR\plugins\command-palette.jar"
  Delete "$INSTDIR\plugins\cubism-tab-filter.jar"
  Delete "$INSTDIR\plugins\deformer-batch-rename.jar"
  Delete "$INSTDIR\plugins\external-psd-edit.jar"
  Delete "$INSTDIR\plugins\frame-animation-import.jar"
  Delete "$INSTDIR\plugins\history-panel.jar"
  Delete "$INSTDIR\plugins\mcp.jar"
  Delete "$INSTDIR\plugins\mesh-edit-mirror-axis-enhance.jar"
  Delete "$INSTDIR\plugins\model-import-3d.jar"
  Delete "$INSTDIR\plugins\palette-label-style.jar"
  Delete "$INSTDIR\plugins\parameter-batch-transfer.jar"
  Delete "$INSTDIR\plugins\parameter-sync.jar"
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
FunctionEnd


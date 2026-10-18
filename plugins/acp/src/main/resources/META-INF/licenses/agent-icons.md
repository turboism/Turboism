# Agent icon sources and acknowledgements

The ACP chat UI takes design inspiration from [Paseo](https://github.com/getpaseo/paseo), including its compact composer, provider identity icons, capability-dependent controls, and collapsible thinking/tool activity. The Swing UI implementation is maintained by Turboism.

Bundled provider marks were sourced from the following upstream files:

| Local SVG and PNG | Upstream source |
|---|---|
| `codex` | [Paseo assets/icons/codex.svg](https://github.com/getpaseo/paseo/tree/9521ee99552d1ecd6c9f103566874778cdf37ec5/packages/app/assets/icons/codex.svg) |
| `claude`, `gemini`, `opencode`, `antigravity` | [Paseo acp-provider-icons.ts](https://github.com/getpaseo/paseo/tree/9521ee99552d1ecd6c9f103566874778cdf37ec5/packages/app/src/assets/acp-provider-icons.ts), keys `claude-acp`, `gemini`, `opencode`, `agy` |
| `pi` | [Paseo pi-acp.svg](https://github.com/getpaseo/paseo/tree/9521ee99552d1ecd6c9f103566874778cdf37ec5/packages/app/src/assets/acp-provider-icons/pi-acp.svg) |
| `devin` | [Lobe Icons devin.svg](https://github.com/lobehub/lobe-icons/blob/8a1b2821bb768c484fe8da20c5e1838b6bf551a9/packages/static-svg/icons/devin.svg) |

Paseo: Copyright (c) 2025-present Mohamed Boudra. Its Apache-2.0 license and third-party component notice are reproduced in [Paseo-LICENSE.txt](Paseo-LICENSE.txt).

Lobe Icons: Copyright (c) 2023 LobeHub. MIT license reproduced in [Lobe-Icons-LICENSE.txt](Lobe-Icons-LICENSE.txt).

Modifications: SVGs are bundled with 64×64 transparent PNG renderings for Swing/HiDPI use; the renderer uses their alpha masks and applies the active theme foreground. SVG source geometry is retained. No upstream runtime or UI component code is bundled.

Agent names and logos remain the property of their respective owners and are used to identify the selected agent. This plugin is not affiliated with or endorsed by Paseo or those agent providers. The applicable third-party notices remain in effect.

Functional UI icons use [Lucide v0.468.0](https://github.com/lucide-icons/lucide/tree/0.468.0/icons): `settings`, `square-terminal`, `zap`, `list-checks`, `plus`, `arrow-up`, `square`, `file-text`, `x`, and `message-square-code`. They are used under the ISC license (including the upstream Feather notice); see [Lucide-LICENSE.txt](Lucide-LICENSE.txt). SVG geometry is unchanged. 80×80 PNG alpha masks support 20px theme-tinted Swing rendering; toolbar variants render `message-square-code` at 32px with normal/hover tints.

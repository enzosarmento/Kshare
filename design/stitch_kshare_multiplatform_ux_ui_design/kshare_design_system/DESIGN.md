---
name: KShare Design System
colors:
  surface: '#0b1326'
  surface-dim: '#0b1326'
  surface-bright: '#31394d'
  surface-container-lowest: '#060e20'
  surface-container-low: '#131b2e'
  surface-container: '#171f33'
  surface-container-high: '#222a3d'
  surface-container-highest: '#2d3449'
  on-surface: '#dae2fd'
  on-surface-variant: '#c2c6d6'
  inverse-surface: '#dae2fd'
  inverse-on-surface: '#283044'
  outline: '#8c909f'
  outline-variant: '#424754'
  surface-tint: '#adc6ff'
  primary: '#adc6ff'
  on-primary: '#002e6a'
  primary-container: '#4d8eff'
  on-primary-container: '#00285d'
  inverse-primary: '#005ac2'
  secondary: '#c0c1ff'
  on-secondary: '#1000a9'
  secondary-container: '#3131c0'
  on-secondary-container: '#b0b2ff'
  tertiary: '#4edea3'
  on-tertiary: '#003824'
  tertiary-container: '#00a572'
  on-tertiary-container: '#00311f'
  error: '#ffb4ab'
  on-error: '#690005'
  error-container: '#93000a'
  on-error-container: '#ffdad6'
  primary-fixed: '#d8e2ff'
  primary-fixed-dim: '#adc6ff'
  on-primary-fixed: '#001a42'
  on-primary-fixed-variant: '#004395'
  secondary-fixed: '#e1e0ff'
  secondary-fixed-dim: '#c0c1ff'
  on-secondary-fixed: '#07006c'
  on-secondary-fixed-variant: '#2f2ebe'
  tertiary-fixed: '#6ffbbe'
  tertiary-fixed-dim: '#4edea3'
  on-tertiary-fixed: '#002113'
  on-tertiary-fixed-variant: '#005236'
  background: '#0b1326'
  on-background: '#dae2fd'
  surface-variant: '#2d3449'
typography:
  headline-xl:
    fontFamily: Inter
    fontSize: 40px
    fontWeight: '700'
    lineHeight: 48px
    letterSpacing: -0.03em
  headline-xl-mobile:
    fontFamily: Inter
    fontSize: 30px
    fontWeight: '700'
    lineHeight: 38px
    letterSpacing: -0.02em
  headline-lg:
    fontFamily: Inter
    fontSize: 28px
    fontWeight: '600'
    lineHeight: 36px
    letterSpacing: -0.02em
  headline-lg-mobile:
    fontFamily: Inter
    fontSize: 22px
    fontWeight: '600'
    lineHeight: 30px
    letterSpacing: -0.01em
  title-md:
    fontFamily: Inter
    fontSize: 18px
    fontWeight: '600'
    lineHeight: 26px
    letterSpacing: -0.01em
  body-lg:
    fontFamily: Inter
    fontSize: 16px
    fontWeight: '400'
    lineHeight: 24px
  body-md:
    fontFamily: Inter
    fontSize: 14px
    fontWeight: '400'
    lineHeight: 20px
  body-sm:
    fontFamily: Inter
    fontSize: 12px
    fontWeight: '400'
    lineHeight: 16px
  label-md:
    fontFamily: JetBrains Mono
    fontSize: 13px
    fontWeight: '500'
    lineHeight: 18px
    letterSpacing: -0.01em
  label-sm:
    fontFamily: JetBrains Mono
    fontSize: 11px
    fontWeight: '500'
    lineHeight: 14px
    letterSpacing: 0.02em
rounded:
  sm: 0.25rem
  DEFAULT: 0.5rem
  md: 0.75rem
  lg: 1rem
  xl: 1.5rem
  full: 9999px
spacing:
  gutter: 1rem
  gutter-desktop: 1.5rem
  margin: 1rem
  margin-desktop: 2rem
  space-xs: 0.25rem
  space-sm: 0.5rem
  space-md: 1rem
  space-lg: 1.5rem
  space-xl: 2.5rem
---

## Brand & Style

### Personality & Philosophy
This design system defines an ultra-fast, local-first cross-platform file transfer utility. The personality balances the mechanical precision of developer utilities (Linear, Raycast) with the accessibility and fluidity of modern operating system interfaces (Material 3, Quick Share). It projects absolute privacy, zero-latency feedback, and effortless reliability. 

### Target Audience & Emotional Impact
Engineers, designers, power users, and everyday multi-device workers transferring sensitive, large files over LAN/Wi-Fi Direct. The interface evokes peace of mind through radical transparency: immediate recognition of local nodes, explicit network topology states, and reassuring deterministic progress.

### Design Movement & Aesthetic Direction
A tailored synthesis of **Modern Utility Minimalist** and **Subtle Glass/Tactile Surface Layering**:
- **HCI First Principles:** Adherence to Nielsen’s heuristics (continuous visibility of system status, real-world match, error prevention), Fitts's Law (large drop-targets and rapid action bars), Hick's Law (staged progressive disclosure from radar discovery to transfer queue), and Miller’s Law (chunked payload summaries).
- **Surface Quality:** Dense, crisp informational layers framed by delicate semi-translucent backdrops, micro-borders, and dynamic status glows rather than heavy skeumorphism.
- **Accessibility:** Built natively for WCAG 2.2 Level AA compliance, featuring distinct focus indicators, target sizes meeting the 24x24px minimum (defaulting to 44px for primary interactive targets), and high visual contrast.

## Colors

### Palette Strategy
The core identity is anchored on an electric, phosphorescent blue-indigo spectrum conveying speed, signal strength, and zero-loss packet delivery. The palette is engineered symmetrically across light and dark modes, defaulting to a dark command-center aesthetic typical of desktop utilities.

### Role Tokens & Semantics
- **Primary (`#3B82F6` Electric Blue):** Drives primary actions, active radar pings, selected states, and real-time progress trajectories. In light mode, this deepens to `#2563EB` for AA contrast compliance on light canvases.
- **Secondary (`#6366F1` Deep Indigo):** Applied to secondary badges, device categorization markers, paired/trusted status indicators, and subtle gradient strokes on key card elevations.
- **Tertiary (`#10B981` Emerald Mint):** Semantic success, secure local peer-to-peer verification, zero-error transfer completions, and active Wi-Fi Direct handshake confirmations.
- **Warning & Error Semantics:** `#F59E0B` (Amber) for throttled connections or pending authorizations; `#EF4444` (Crimson) for aborted transfers, network dropouts, and untrusted peers.
- **Neutral Surface Foundation (`#0F172A` Slate Dark):**
  - *Dark Mode:* Deep slate backgrounds (`#0B0F19`), surface containers (`#111827`, `#1E293B`), subtle border strokes (`rgba(255, 255, 255, 0.08)`), and text primary (`#F8FAFC`).
  - *Light Mode:* Pure base (`#F8FAFC`), elevated surfaces (`#FFFFFF`, `#F1F5F9`), structure lines (`rgba(15, 23, 42, 0.08)`), and text primary (`#0F172A`).

## Typography

### Typographic Hierarchy
The typographic scale combines the ubiquitous, neutral readability of `Inter` for prose and operational headers with `JetBrains Mono` for technical readouts, IP/PIN authentication codes, byte counters, transfer speeds, and port identifiers.

### Behavioral Rules
- **Tabular Figures:** Always apply `font-feature-settings: "tnum" 1` to technical metrics, transfer speed rates (e.g., `48.2 MB/s`), percentages, and remaining countdown timers to eliminate layout jitter during high-throughput transfers.
- **Information Chunking:** Subtitles and file paths must truncate via middle-ellipsis (e.g., `archive_build_..._v2.tar.gz`) rather than standard trailing truncation, ensuring critical extensions and target signatures remain legible.

## Layout & Spacing

### Layout Architecture
- **Master Grid:** Responsive fluid grid with responsive containers. Mobile platforms use a single-column stacked view with a persistent bottom quick-action sheet. Tablets and desktop environments (macOS, Windows, Linux) adopt a split-panel architectural layout: a fixed navigation and discovery radar pane (280px–340px) alongside an adaptive multi-column file drop and transfer monitor.
- **Fitts's Law Drop Zones:** Large, unambiguous drag-and-drop target regions occupying a minimum of 40% of the active desktop canvas when transfers are pending.
- **Breakpoints:**
  - `sm` (< 640px): 4-column layout, bottom-sheet interactions, tap target expansion (48px height minimum).
  - `md` (640px–1024px): 8-column layout, hybrid sheet/modal presentation.
  - `lg` (> 1024px): 12-column layout, docked sidebar, split panes, keyboard shortcut guides displayed inline.

## Elevation & Depth

### Visual Stacking Paradigm
Depth is defined primarily through **tonal container tiers** supplemented with micro-borders and soft ambient tinted glows. Traditional drop shadows are discarded to avoid visual clutter and maintain high UI performance during heavy CPU/network load.

### Surface Tiers
- **Tier 0 (Base Canvas):** Ground surface (`#0B0F19` dark / `#F8FAFC` light).
- **Tier 1 (Surface Container):** Neutral panels, sidebars, passive card sections (`#111827` dark / `#FFFFFF` light) framed by a 1px border (`rgba(255, 255, 255, 0.06)` dark / `rgba(15, 23, 42, 0.08)` light).
- **Tier 2 (Elevated Card / Radar Node):** Interactive peer nodes, hovering files (`#1E293B` dark / `#F1F5F9` light) with subtle diffuse glow (`0 8px 24px -4px rgba(59, 130, 246, 0.12)`).
- **Tier 3 (Modals / Transfer Overlays / System Alerts):** Top-level dialogs with `backdrop-filter: blur(16px)` and surface illumination (`rgba(30, 41, 59, 0.85)` dark / `rgba(255, 255, 255, 0.90)` light).

## Shapes

### Corner Curvature Language
The system adopts an intentional, modern curved architecture matching contemporary OS standards:
- **Base Components (`roundedness: 2` / 0.5rem / 8px):** Form fields, technical badges, status chips, tooltips.
- **Cards & Devices (`rounded-lg` / 1rem / 16px):** Peer discovery target cards, file preview widgets, multi-file queue containers.
- **Modal Dialogs & Radar Pods (`rounded-xl` / 1.5rem / 24px):** Global transfer dialogs, incoming transmission notifications, drop-zone envelopes.
- **Pill Geometry (`rounded-full`):** Action chips, device connection type tags (e.g., `Wi-Fi 6`, `BLE`, `LAN`), network protocol pills, and progress track caps.

## Components

### Buttons
- **Primary:** Solid `#3B82F6`, high-contrast text (`#FFFFFF`), 0.5rem radius, height 40px (desktop) / 48px (mobile). Includes micro-scale feedback on active press (`scale: 0.98`).
- **Ghost / Utility:** Transparent fill, `rgba(255, 255, 255, 0.08)` stroke, subtle hover tint.
- **Destructive:** Soft crimson background tint with crisp `#EF4444` label for session cancellation or blacklisting.

### Discovery Radar & Peer Nodes
- Concentric pulse animations signaling active discovery mode, throttled via CSS to conserve device battery.
- Peer nodes render the device type icon (Mobile, Laptop, Desktop, Server), operating system emblem, custom peer nickname, and signal strength RSSI badge.
- Pointers indicate direct line-of-sight status; tapping initiates the zero-handshake request modal.

### Transfer Progress Bars
- Dual-indicator tracks: overall payload bar and active file segment sub-bar.
- Dynamic fill styled with an animated gradient pulse during transmission, instantly shifting to static `#10B981` on cryptographic checksum verification (`SHA-256 complete`).
- Monospaced throughput (`label-md`) anchored persistently to the right: `[45.8 MB/s · ETA 00:14]`.

### Connectivity & Trust Badges
- Compact pills utilizing `label-sm` with a 6px breathing dot indicator: Green (Verified/Mutual Trust PIN), Blue (LAN Connected), Amber (Waiting for Peer Approval).

### Form Inputs & Drop Zones
- Drag-and-drop targets toggle to a vibrant dotted blue glow with `2px dashed #3B82F6` and a translucent `#3B82F610` overlay upon drag-enter.
- Inputs for manual IP/Port or paired 6-digit PIN use oversized monospace letterforms with auto-advance and automatic paste capture.

### Toast & Notification Overlays
- Floating bottom-center or top-right cards displaying peer thumbnail, batch size, and rapid inline actions (`Accept` / `Decline`) with auto-dismiss timers visible as shrinking linear borders.
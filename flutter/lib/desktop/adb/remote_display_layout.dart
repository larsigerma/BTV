import 'package:flutter/material.dart';
import 'package:get/get.dart';

import '../../consts.dart';
import '../../models/model.dart';
import '../../models/platform_model.dart' show bind;
import 'remote_adb_mirror.dart';
import 'remote_adb_mirror_view.dart';

/// The ordinary canvas stays at (0, 0); only its viewport width changes.
/// Both children stay mounted when a source closes, preserving their session ownership.
class RemoteDisplayLayout extends StatefulWidget {
  const RemoteDisplayLayout({super.key, required this.ffi, required this.mirror, required this.ordinary,
    required this.onAdbFocusRequested});
  final FFI ffi;
  final RemoteAdbMirror mirror;
  final Widget ordinary;
  final VoidCallback onAdbFocusRequested;

  @override
  State<RemoteDisplayLayout> createState() => _RemoteDisplayLayoutState();
}

class _RemoteDisplayLayoutState extends State<RemoteDisplayLayout> {
  bool _ordinaryVisible = false;
  bool? _ordinaryEnabled;
  bool _split = false;
  int _layoutRevision = 0;

  void _trackOrdinaryLayout(bool split) {
    if (_split == split) return;
    final restore = _split && !split && _ordinaryVisible &&
        !widget.mirror.hasFrame.value;
    _split = split;
    final revision = ++_layoutRevision;
    if (!restore) return;
    final ffi = widget.ffi;
    final peer = ffi.ffiModel.pi;
    bool current() => mounted && revision == _layoutRevision &&
        identical(ffi, widget.ffi) && identical(peer, ffi.ffiModel.pi) &&
        peer.isSet.value && !ffi.ffiModel.waitForFirstImage.value &&
        _ordinaryVisible && _ordinaryEnabled != false &&
        !widget.mirror.hasFrame.value && widget.mirror.phase != 'starting';

    WidgetsBinding.instance.addPostFrameCallback((_) async {
      if (!current()) return;
      try {
        await ffi.canvasModel.updateViewStyle(refreshMousePos: false);
        if (!current()) return;
        if (!ffi.imageModel.useTextureRender && !peer.forceTextureRender) return;
        ffi.textureModel.repaintTextures();
        // This only rearms the local next-frame notification. Do not refresh
        // the remote encoder or restart capture when an independent ADB pane closes.
        await bind.sessionOnWaitingForImageDialogShow(sessionId: ffi.sessionId);
      } catch (_) {
        // A closing/reconnecting page must not turn a local repaint into a
        // remote operation or prevent the ADB stop from completing.
      }
    });
  }

  @override
  Widget build(BuildContext context) => AnimatedBuilder(
    animation: Listenable.merge([widget.mirror, widget.mirror.hasFrame, widget.ffi.ashellStatusModel, widget.ffi.ffiModel]),
    builder: (context, _) => Obx(() {
      final model = widget.ffi.ffiModel;
      final status = widget.ffi.ashellStatusModel.data;
      final firstFrame = !model.waitForFirstImage.value;
      final ordinaryOff = status.video == false && status.screenshot == false && status.penetrate != true;
      if (ordinaryOff) _ordinaryEnabled = false;
      else if (status.video == true || status.screenshot == true || status.penetrate == true) _ordinaryEnabled = true;
      if (_ordinaryEnabled == false) {
        _ordinaryVisible = false;
      } else if (firstFrame && model.pi.isSet.value) {
        _ordinaryVisible = true;
      }
      // Unknown/stale status preserves the last picture during the original reconnect window.
      final adb = widget.mirror.hasFrame.value;
      final waitingForFirstFrame = model.androidConnectedWaitingForImage.value &&
          model.waitForFirstImage.value;
      // Keep the connection hint separate from ADB's own startup/frames.
      final waitingText = waitingForFirstFrame && !adb && !widget.mirror.active &&
          widget.mirror.error.isEmpty ? '已连接，等待画面传输' : null;
      final showOrdinary = _ordinaryVisible || waitingText != null;
      final both = adb && showOrdinary;
      _trackOrdinaryLayout(both);
      widget.ffi.canvasModel.setDesktopAlignRight(both);
      return LayoutBuilder(builder: (context, constraints) {
        final width = constraints.maxWidth;
        final dividerWidth = width.clamp(0.0, 1.0).toDouble();
        final ordinaryWidth = both ? (width - dividerWidth) / 2 : width;
        return ColoredBox(color: kColorCanvas, child: Stack(fit: StackFit.expand, children: [
          Positioned(key: const ValueKey('ordinary-display'), left: 0, top: 0, bottom: 0, width: ordinaryWidth,
            child: Offstage(offstage: !showOrdinary,
              child: ClipRect(child: Stack(fit: StackFit.expand, children: [
                Offstage(offstage: !_ordinaryVisible, child: widget.ordinary),
                if (waitingText != null) IgnorePointer(child: Center(
                  child: Padding(padding: const EdgeInsets.all(24),
                    child: Text(waitingText, textAlign: TextAlign.center,
                      style: const TextStyle(color: Colors.white70, fontSize: 14,
                        shadows: [Shadow(color: Colors.black87, blurRadius: 4)]))))),
              ])))),
          Positioned(key: const ValueKey('adb-display'), left: both ? ordinaryWidth + dividerWidth : 0, top: 0, right: 0, bottom: 0,
            child: Offstage(offstage: !adb && showOrdinary,
              child: ClipRect(child: AnimatedBuilder(animation: widget.mirror, builder: (context, _) =>
                adb || widget.mirror.active || widget.mirror.error.isNotEmpty
                    ? RemoteAdbMirrorView(mirror: widget.mirror, onFocusRequested: widget.onAdbFocusRequested,
                        alignment: both ? Alignment.centerLeft : Alignment.center)
                    : const SizedBox.expand())))),
          if (both) Positioned(left: ordinaryWidth, top: 0, bottom: 0, width: dividerWidth,
            child: ColoredBox(color: Theme.of(context).dividerColor.withOpacity(0.65))),
        ]));
      });
    }));
}

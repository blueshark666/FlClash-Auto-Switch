import 'dart:async';
import 'dart:convert';
import 'dart:isolate';
import 'dart:developer';

import 'package:fl_clash/common/constant.dart';
import 'package:fl_clash/enum/enum.dart';
import 'package:fl_clash/common/system.dart';
import 'package:fl_clash/models/common.dart';
import 'package:fl_clash/models/core.dart';
import 'package:fl_clash/state.dart';
import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';

abstract mixin class ServiceListener {
  void onServiceEvent(CoreEvent event) {}

  void onServiceCrash(String message) {}
}

class Service {
  static Service? _instance;
  late MethodChannel methodChannel;
  ReceivePort? receiver;

  final ObserverList<ServiceListener> _listeners =
      ObserverList<ServiceListener>();

  factory Service() {
    _instance ??= Service._internal();
    return _instance!;
  }

  Service._internal() {
    methodChannel = const MethodChannel('$packageName/service');
    methodChannel.setMethodCallHandler((call) async {
      switch (call.method) {
        case 'getVpnOptions':
          return handleGetVpnOptions();
        case 'event':
          final data = call.arguments as String? ?? '';
          final result = ActionResult.fromJson(json.decode(data));
          for (final listener in _listeners) {
            listener.onServiceEvent(CoreEvent.fromJson(result.data));
          }
          break;
        case 'crash':
          final message = call.arguments as String? ?? '';
          for (final listener in _listeners) {
            listener.onServiceCrash(message);
          }
          break;
        case 'message':
          final data = call.arguments as String? ?? '';
          try {
            final jsonData = json.decode(data) as Map<String, dynamic>;
            final type = jsonData['type'] as String?;
            
            if (type == 'updateConfig' && globalState.isInit) {
              final configData = jsonData['data'] as Map<String, dynamic>;
              final modeStr = configData['mode'] as String?;
              
              if (modeStr != null) {
                // 将字符串转换为 Mode 枚举
                Mode mode;
                switch (modeStr) {
                  case 'global':
                    mode = Mode.global;
                    break;
                  case 'direct':
                    mode = Mode.direct;
                    break;
                  case 'rule':
                  default:
                    mode = Mode.rule;
                    break;
                }
                
                // 保存旧模式用于可能的回滚
                final oldMode = globalState.config.patchClashConfig.mode;
                
                // 通过 appController 更新配置模式（这会立即更新 UI）
                globalState.appController.changeMode(mode);
                
                // 然后同步到 core
                globalState.appController.updateClashConfig().catchError((error) {
                  // 如果同步失败，回滚到旧模式
                  debugPrint('Failed to update config to core: $error');
                  globalState.appController.changeMode(oldMode);
                  
                  // 错误处理：同步失败日志记录
                  debugPrint('Configuration update failed, rolled back to previous mode.');
                });
              }
            }
          } catch (e) {
            debugPrint('Error parsing message: $e');
          }
          break;
        default:
          throw MissingPluginException();
      }
    });
  }

  Future<ActionResult?> invokeAction(Action action) async {
    final data = await methodChannel.invokeMethod<String>(
      'invokeAction',
      json.encode(action),
    );
    if (data == null) {
      return null;
    }
    return ActionResult.fromJson(json.decode(data));
  }

  String handleGetVpnOptions() {
    return json.encode(globalState.getVpnOptions());
  }

  Future<bool> start() async {
    return await methodChannel.invokeMethod<bool>('start') ?? false;
  }

  Future<bool> stop() async {
    return await methodChannel.invokeMethod<bool>('stop') ?? false;
  }

  Future<String> syncAndroidState(AndroidState state) async {
    return await methodChannel.invokeMethod<String>(
          'syncState',
          json.encode(state),
        ) ??
        '';
  }

  Future<String> init() async {
    return await methodChannel.invokeMethod<String>(
          'init',
          !globalState.isService,
        ) ??
        '';
  }

  Future<bool> shutdown() async {
    return await methodChannel.invokeMethod<bool>('shutdown') ?? true;
  }

  Future<DateTime?> getRunTime() async {
    final ms = await methodChannel.invokeMethod<int>('getRunTime') ?? 0;
    if (ms == 0) {
      return null;
    }
    return DateTime.fromMillisecondsSinceEpoch(ms);
  }

  bool get hasListeners {
    return _listeners.isNotEmpty;
  }

  void addListener(ServiceListener listener) {
    _listeners.add(listener);
  }

  void removeListener(ServiceListener listener) {
    _listeners.remove(listener);
  }
}

Service? get service => system.isAndroid ? Service() : null;

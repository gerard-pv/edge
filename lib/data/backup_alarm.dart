// Native wake-ups for the scheduled backup (`openstrap/backup_alarm`).
//
// A Dart Timer alone does not keep a time: it counts monotonic time, which
// stops while the phone sleeps, and nothing else holds the CPU awake.
//
// Android (BackupAlarm.kt): an AlarmManager alarm at the next due time. It
// fires through Doze — exactly when the user allows exact alarms, otherwise
// allow-while-idle, which Doze can defer by some minutes. A wake lock is held
// from the alarm until [holdAwake] releases it. With no Dart running, the
// alarm starts the engine and leaves a flag [init] picks up.
//
// iOS (AppDelegate.swift): no alarm exists for a suspended app. A backup starts
// from a band wake, the BGProcessingTask or the next open; [holdAwake] asks iOS
// for background time so it can finish.
//
// Every call is best-effort. A missing channel (tests, desktop) means the
// foreground hook and the Dart timer are all that run, as before.

import 'dart:async';
import 'dart:io';

import 'package:flutter/services.dart';

import 'auto_backup.dart';

class BackupAlarm {
  BackupAlarm._();

  static const _ch = MethodChannel('openstrap/backup_alarm');

  /// AppState's scheduled run while one exists. Null in a headless engine,
  /// where the alarm runs [runStoredBackupIfDue] directly instead.
  static Future<void> Function()? onFire;

  static bool get _supported => Platform.isAndroid || Platform.isIOS;

  /// Install the handler and run a backup an alarm left pending while no Dart
  /// was running. Call once from main(), before runApp.
  static Future<void> init() async {
    if (!_supported) return;
    _ch.setMethodCallHandler((call) async {
      if (call.method == 'fire') await _fire();
      return null;
    });
    if (!Platform.isAndroid) return;
    try {
      if (await _ch.invokeMethod<bool>('takePending') == true) {
        unawaited(_fire());
      }
    } catch (_) {}
  }

  static Future<void> _fire() async {
    final handler = onFire;
    if (handler != null) return handler();
    await holdAwake(runStoredBackupIfDue);
    await schedule(storedNextBackupWake());
  }

  /// Set the Android alarm for [at], or clear it when null. No-op on iOS.
  static Future<void> schedule(DateTime? at) async {
    if (!Platform.isAndroid) return;
    try {
      await _ch.invokeMethod('schedule', {'atMs': at?.millisecondsSinceEpoch});
    } catch (_) {}
  }

  /// Whether Android lets the alarm fire on the minute. True elsewhere, where
  /// there is no such setting to send anyone to.
  static Future<bool> canScheduleExact() async {
    if (!Platform.isAndroid) return true;
    try {
      return await _ch.invokeMethod<bool>('canScheduleExact') ?? false;
    } catch (_) {
      return true;
    }
  }

  /// Open Android's "Alarms & reminders" page for this app.
  static Future<void> requestExact() async {
    if (!Platform.isAndroid) return;
    try {
      await _ch.invokeMethod('requestExact');
    } catch (_) {}
  }

  /// Keep the phone awake (Android) or the app running (iOS) while [body]
  /// runs, then let go — whether [body] succeeds or throws.
  static Future<T> holdAwake<T>(Future<T> Function() body) async {
    if (!_supported) return body();
    try {
      await _ch.invokeMethod('acquire');
    } catch (_) {}
    try {
      return await body();
    } finally {
      try {
        await _ch.invokeMethod('release');
      } catch (_) {}
    }
  }
}

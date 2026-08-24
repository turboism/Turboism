'use strict';
const blocked = (name, action) => {
  try {
    action();
    console.log(`${name}=EXPOSED`);
  } catch (_) {
    console.log(`${name}=blocked`);
  }
};
blocked('java', () => Java.type('java.lang.System'));
blocked('load', () => load('file:///C:/windows/win.ini'));
blocked('process', () => Polyglot.import('java.lang.ProcessBuilder'));
blocked('thread', () => new Worker('while (true) {}'));
blocked('fetch', () => fetch('http://127.0.0.1/'));

'use strict';
const fs = require('fs');
const { execFileSync } = require('child_process');

const htmlPath = 'GomrokYar_Android/app/src/main/assets/index.html';
let html = fs.readFileSync(htmlPath, 'utf8');
const scriptPattern = /<script\\b([^>]*)>([\\s\\S]*?)<\\/script>/gi;
let transformedCount = 0;

html = html.replace(scriptPattern, (whole, attributes, source) => {
  if (/\\bsrc\\s*=/.test(attributes) || !source.trim()) return whole;
  const output = execFileSync('npx', [
    '--yes', 'esbuild@0.25.12',
    '--target=chrome51',
    '--loader=js',
    '--log-level=warning'
  ], { input: source, encoding: 'utf8', maxBuffer: 32 * 1024 * 1024 });
  transformedCount += 1;
  return '<script' + attributes + '>' + output + '</script>';
});

const polyfillMarker = 'id="android7-compat-polyfills"';
if (!html.includes(polyfillMarker)) {
  const polyfills = `<script id="android7-compat-polyfills">
(function () {
  if (!String.prototype.replaceAll) {
    Object.defineProperty(String.prototype, 'replaceAll', {
      configurable: true, writable: true,
      value: function (search, replacement) {
        var source = String(this);
        if (search instanceof RegExp) {
          if (!search.global) throw new TypeError('replaceAll requires a global RegExp');
          return source.replace(search, replacement);
        }
        return source.split(String(search)).join(String(replacement));
      }
    });
  }
  if (!String.prototype.padStart) {
    Object.defineProperty(String.prototype, 'padStart', {
      configurable: true, writable: true,
      value: function (targetLength, padString) {
        var source = String(this);
        var fill = padString === undefined ? ' ' : String(padString);
        var needed = Math.max(0, Number(targetLength) - source.length);
        if (!needed || !fill) return source;
        while (fill.length < needed) fill += fill;
        return fill.slice(0, needed) + source;
      }
    });
  }
  if (!Object.entries) {
    Object.entries = function (value) {
      var object = Object(value), result = [], key;
      for (key in object) {
        if (Object.prototype.hasOwnProperty.call(object, key)) result.push([key, object[key]]);
      }
      return result;
    };
  }
})();
</script>\n`;
  html = html.replace(/<script\\b/i, polyfills + '<script');
}
fs.writeFileSync(htmlPath, html, 'utf8');
console.log('Android 7 WebView compatibility: transpiled ' + transformedCount + ' inline scripts for Chrome 51 and installed legacy API polyfills.');

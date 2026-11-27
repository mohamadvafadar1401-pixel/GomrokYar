'use strict';
const fs = require('fs');
const html = fs.readFileSync('GomrokYar_Android/app/src/main/assets/index.html', 'utf8');
const scripts = [...html.matchAll(/<script\b([^>]*)>([\s\S]*?)<\/script>/gi)]
  .filter(([, attrs, body]) => !/\bsrc\s*=/.test(attrs) && body.trim());
if (scripts.length < 2) throw new Error('Expected inline login and app scripts were not found.');
for (let i = 0; i < scripts.length; i++) {
  try { new Function(scripts[i][2]); }
  catch (error) { throw new Error('Inline script #' + (i + 1) + ' syntax error: ' + error.message); }
}
const required = ['authGate','authScene','authCard','firstAdminSetup','setupUsername','setupPassword','setupPassword2','createAdminBtn','userLogin','loginUsername','loginPassword','loginBtn','authError'];
for (const id of required) if (!html.includes('id="' + id + '"')) throw new Error('Login markup missing: ' + id);
for (const fn of ['showAuth','initAuth','unlockAs']) {
  if (!new RegExp('function\\s+' + fn + '\\s*\\(').test(html)) throw new Error('Login function missing: ' + fn);
}
console.log('LOGIN PREFLIGHT PASSED: ' + scripts.length + ' inline scripts parse; required login markup and auth functions exist.');

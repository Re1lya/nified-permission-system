// Install @viz-js/viz and @resvg/resvg-js in a separate tools directory;
// point NODE_PATH at its node_modules. No project runtime dependencies change.
const { instance } = require('@viz-js/viz');
const { Resvg } = require('@resvg/resvg-js');
const fs = require('node:fs');
const path = require('node:path');
(async () => {
  const viz = await instance();
  const dir = path.resolve(__dirname, '../docs/phase-one/images');
  for (const key of ['full','rbac','company','platform','protocol']) {
    const base = path.join(dir, 'er-'+key);
    const svg = viz.renderString(fs.readFileSync(base+'.dot','utf8'), {format:'svg',engine:'dot'});
    fs.writeFileSync(base+'.svg',svg);
    const png = new Resvg(svg, {fitTo:{mode:'width',value:key==='full'?4200:2600},font:{loadSystemFonts:true,defaultFontFamily:'Arial'}}).render().asPng();
    fs.writeFileSync(base+'.png',png);
    console.log('Rendered',key);
  }
})();

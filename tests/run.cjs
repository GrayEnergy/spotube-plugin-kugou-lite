const {describe, it} = require("node:test");
global.describe = describe;
global.it = it;
require("../plugin/build/compileSync/js/test/testDevelopmentExecutable/kotlin/spotube-plugin-kugou-lite-plugin-test.js");

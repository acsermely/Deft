// Platform Check: one button per PARITY Phase A item. Results go to #log and the console ("PC ...").
import '/_deft/v1/motion.js';
import { log } from './log.js';
import { wire } from './checks.js';

wire();
log('ready', true, 'deftBridge=' + !!window.deftBridge);

let theme = 'light';
const frame = document.querySelector('iframe');
const state = document.querySelector('#state');
function sendTheme(){frame.contentWindow.postMessage({type:'study-theme',theme},location.origin)}
document.querySelectorAll('[data-theme]').forEach(button=>button.addEventListener('click',()=>{theme=button.dataset.theme;document.documentElement.dataset.theme=theme;document.querySelectorAll('[data-theme]').forEach(item=>item.setAttribute('aria-pressed',String(item===button)));sendTheme()}));
state.addEventListener('change',()=>{frame.src='../slim-a-flow/index.html?'+new URLSearchParams({view:'home',state:state.value,theme,revision:'distill-final-1'})});
frame.addEventListener('load',sendTheme);

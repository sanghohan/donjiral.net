/* 앱별 게시판 — 앱 페이지의 <body data-board="앱"> 와 <div id="board"> 에 붙는다.
   글은 api.donjiral.net 의 게시판(board=앱) 에 저장된다. 마크다운(marked + DOMPurify), 코드 강조(highlight.js) 는 페이지가 먼저 불러온다.
   글 바로가기 주소: /apps/<앱>/?post=번호 (앱 등에서 소개·설명서 글로 바로 연결) */
(function(){
const $=s=>document.querySelector(s),$$=s=>document.querySelectorAll(s);
const BOARD=document.body.dataset.board;

let API_BASE = "https://api.donjiral.net";
API_BASE = localStorage.getItem('pingpong_api') || API_BASE;
const USE_API = !!API_BASE;

const CATS=window.BOARD_CATS||[   // 페이지가 window.BOARD_CATS 로 분류를 바꿀 수 있다 (예: /ideas/)
  {k:'notice', n:'공지사항', e:'📢'},
  {k:'qna', n:'질문답변', e:'💬'},
  {k:'free', n:'자유게시판', e:'☕'}
];
const catName=k=>(CATS.find(c=>c.k===k)||{n:'기타',e:'📱'});
let filter='전체';

// 검색창과 글쓰기 칸을 각자 <form> 으로 나누고 autocomplete 를 끈다. 폼 없이 비밀번호 칸과 한데 섞여 있으면
// 크롬 비밀번호 관리자가 첫 글자 칸(검색창)을 아이디 칸으로 보고 저장된 아이디를 채워, 목록이 그 글자로 걸러졌다.
$('#board').innerHTML=`
  <div id="banner"></div>

  <div class="card" id="listView">
    <form class="toolbar" role="search" autocomplete="off" onsubmit="return false">
      <input id="search" type="search" name="q" autocomplete="off" placeholder="🔍 제목·내용·작성자 검색">
      <button class="btn" type="button" id="writeBtn">✏️ 글쓰기</button>
    </form>
    <div class="cats" id="cats"></div>
    <div class="list" id="list"></div>
    <div class="pager" id="pager"></div>
  </div>

  <div class="card detail hidden" id="detailView"></div>

  <form class="card form hidden" id="writeView" autocomplete="off" onsubmit="return false">
    <h2 style="font-size:18px" id="writeTitle">✏️ 새 글 쓰기</h2>
    <label>게시판</label>
    <select id="wCat"></select>
    <label>제목</label>
    <input id="wTitle" autocomplete="off" placeholder="제목을 입력하세요">
    <label id="authorLabel">작성자</label>
    <input id="wAuthor" autocomplete="off" placeholder="닉네임">
    <div id="adminRow" class="hidden">
      <label>🔑 관리자 암호 — ‘돈지랄’ 이름은 관리자만 쓸 수 있어요</label>
      <input id="wAdmin" type="password" autocomplete="new-password" placeholder="관리자 암호">
    </div>
    <label>이미지/영상 — 링크를 붙여넣으세요 (파일 업로드는 중단되었습니다)</label>
    <input type="file" id="wFile" accept="image/*,video/*" style="display:none" disabled>
    <div id="uploadState" class="note"></div>
    <input id="wMedia" autocomplete="off" placeholder="https://... (jpg, png, mp4, youtube 링크)" style="margin-top:6px">
    <label>내용 — <b>마크다운</b> 지원 (코드는 \`\`\`언어 로 감싸면 색상 강조)</label>
    <textarea id="wContent" placeholder="## 제목&#10;설명...&#10;&#10;\`\`\`python&#10;print('hello')&#10;\`\`\`"></textarea>
    <label>비밀번호 — 수정/삭제할 때 필요해요</label>
    <input id="wPassword" type="password" autocomplete="new-password" placeholder="글 비밀번호">
    <div class="toolbar" style="margin-top:14px;margin-bottom:0">
      <button class="btn" type="button" id="submitBtn">등록</button>
      <button class="btn sec" type="button" id="cancelBtn">취소</button>
    </div>
  </form>`;

/* ---------- 데이터 계층 ---------- */
const LS='board_'+BOARD+'_v1';
function lsLoad(){try{const o=JSON.parse(localStorage.getItem(LS));if(o&&o.posts)return o;}catch(e){}return {posts:[],nextId:100,nextCid:1000};}
function lsSave(d){localStorage.setItem(LS,JSON.stringify(d));}

async function apiFetch(path,opts){
  const r=await fetch(API_BASE+path,{headers:{'Content-Type':'application/json'},...opts});
  if(!r.ok){let m='요청 실패 ('+r.status+')';try{const j=await r.json();if(j.message)m=j.message;}catch(e){}throw new Error(m);}
  if(r.status===204)return null;
  return r.json();
}

const PAGE_SIZE=10;
const data={
  async list(cat,q,page){
    if(USE_API){
      const p=new URLSearchParams();p.set('board',BOARD);if(cat&&cat!=='전체')p.set('category',cat);if(q)p.set('q',q);
      p.set('page',page);p.set('size',PAGE_SIZE);
      return apiFetch('/api/posts?'+p.toString());
    }
    const d=lsLoad();let posts=d.posts.slice().sort((a,b)=>b.createdAt-a.createdAt);
    if(cat&&cat!=='전체')posts=posts.filter(p=>p.category===cat);
    if(q){const s=q.toLowerCase();posts=posts.filter(p=>(p.title+p.content+p.author).toLowerCase().includes(s));}
    const total=posts.length,totalPages=Math.max(1,Math.ceil(total/PAGE_SIZE));
    const items=posts.slice(page*PAGE_SIZE,(page+1)*PAGE_SIZE).map(p=>({...p,commentCount:p.comments.length}));
    return {items,page,size:PAGE_SIZE,totalPages,totalElements:total};
  },
  async get(id){
    if(USE_API)return apiFetch('/api/posts/'+id);
    const d=lsLoad();const p=d.posts.find(x=>x.id===id);if(!p)throw new Error('글 없음');
    p.views++;lsSave(d);return p;
  },
  async create(body){
    if(USE_API)return apiFetch('/api/posts',{method:'POST',body:JSON.stringify({...body,board:BOARD})});
    const d=lsLoad();const p={id:d.nextId++,board:BOARD,category:body.category,title:body.title,author:body.author||'익명',
      content:body.content,media:body.media||'',likes:0,views:0,comments:[],createdAt:Date.now(),_pw:body.password};
    d.posts.push(p);lsSave(d);return p;
  },
  async update(id,body){
    if(USE_API)return apiFetch('/api/posts/'+id,{method:'PUT',body:JSON.stringify(body)});
    const d=lsLoad();const p=d.posts.find(x=>x.id===id);if(!p)throw new Error('글 없음');
    if(p._pw&&p._pw!==body.password)throw new Error('비밀번호가 일치하지 않습니다');
    p.title=body.title;p.content=body.content;p.media=body.media||'';lsSave(d);return p;
  },
  async upload(file){
    if(USE_API){
      const fd=new FormData();fd.append('file',file);
      const r=await fetch(API_BASE+'/api/uploads',{method:'POST',body:fd});
      if(!r.ok){let m='업로드 실패 ('+r.status+')';try{const j=await r.json();if(j.message)m=j.message;}catch(e){}throw new Error(m);}
      return (await r.json()).url;
    }
    return await new Promise((res,rej)=>{const fr=new FileReader();fr.onload=()=>res(fr.result);fr.onerror=rej;fr.readAsDataURL(file);});
  },
  async remove(id,password){
    if(USE_API)return apiFetch('/api/posts/'+id,{method:'DELETE',body:JSON.stringify({password})});
    const d=lsLoad();const p=d.posts.find(x=>x.id===id);
    if(p&&p._pw&&p._pw!==password)throw new Error('비밀번호가 일치하지 않습니다');
    d.posts=d.posts.filter(x=>x.id!==id);lsSave(d);return null;
  },
  async like(id){
    if(USE_API)return apiFetch('/api/posts/'+id+'/like',{method:'POST'});
    const d=lsLoad();const p=d.posts.find(x=>x.id===id);p.likes++;lsSave(d);return {likes:p.likes};
  },
  async comment(id,body){
    if(USE_API)return apiFetch('/api/posts/'+id+'/comments',{method:'POST',body:JSON.stringify(body)});
    const d=lsLoad();const p=d.posts.find(x=>x.id===id);
    const c={id:d.nextCid++,author:body.author||'익명',content:body.content,createdAt:Date.now()};
    p.comments.push(c);lsSave(d);return c;
  }
};

/* ---------- 유틸 ---------- */
function fmt(t){if(!t)return '';const ms=typeof t==='number'?t:new Date(t).getTime();
  if(!Number.isFinite(ms))return '';
  const d=new Date(ms),p=n=>String(n).padStart(2,'0');
  return `${d.getFullYear()}-${p(d.getMonth()+1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}`;}
function esc(s){return (s||'').replace(/[&<>"]/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;'}[c]));}
function isImg(u){return /^data:image\//.test(u)||/\.(jpg|jpeg|png|gif|webp)(\?|$)/i.test(u);}
function isVid(u){return /^data:video\//.test(u)||/\.(mp4|webm|ogg)(\?|$)/i.test(u);}
let page=0;

/* 마크다운 렌더 (XSS 방지: DOMPurify) */
marked.setOptions({gfm:true,breaks:true});
function renderMarkdown(md){return DOMPurify.sanitize(marked.parse(md||''));}

const RESERVED=['돈지랄'];
const normalizeName=s=>(s||'').toLowerCase().replace(/[^\p{L}\p{N}]/gu,'');
function isReservedName(s){const a=normalizeName(s);return RESERVED.some(r=>a.includes(normalizeName(r)));}

/* ---------- 배너 ---------- */
function showBanner(){
  const b=$('#banner');
  if(USE_API){b.className='';b.innerHTML='';b.style.display='none';}
  else{b.style.display='';b.className='banner local';b.innerHTML='ℹ️ 미리보기 모드 — 글이 <b>이 브라우저에만</b> 저장돼요.';}
}
function bannerError(msg){const b=$('#banner');b.style.display='';b.className='banner err';b.innerHTML='⚠️ '+esc(msg);}

/* ---------- 카테고리 ---------- */
function renderCats(){
  const all=['전체',...CATS.map(c=>c.k)];
  $('#cats').innerHTML=all.map(k=>{
    const lab=k==='전체'?'전체':catName(k).e+' '+catName(k).n;
    return `<div class="cat ${filter===k?'on':''}" data-k="${k}">${lab}</div>`;
  }).join('');
  $$('#cats .cat').forEach(c=>c.onclick=()=>{filter=c.dataset.k;page=0;renderList();renderCats();});
}

/* ---------- 목록 ---------- */
async function renderList(){
  $('#list').innerHTML='<div class="empty">불러오는 중…</div>';$('#pager').innerHTML='';
  let res;
  try{res=await data.list(filter,($('#search').value||'').trim(),page);}
  catch(e){bannerError('목록을 불러오지 못했어요: '+e.message);$('#list').innerHTML='<div class="empty">불러오기 실패</div>';return;}
  showBanner();
  const posts=res.items||[];
  if(!posts.length){$('#list').innerHTML='<div class="empty">글이 없어요. 첫 글을 남겨보세요! ✏️</div>';return;}
  $('#list').innerHTML=posts.map(p=>{
    const c=catName(p.category);
    const thumb=p.media&&isImg(p.media)?`<img class="thumb" src="${esc(p.media)}" onerror="this.style.display='none'">`:'';
    return `<div class="post" data-id="${p.id}">${thumb}
      <div class="body">
        <div class="badge">${c.e} ${c.n}</div>
        <div class="title">${esc(p.title)}</div>
        <div class="meta"><span>👤 ${esc(p.author)}</span><span>🕒 ${fmt(p.createdAt)}</span>
          <span>👁 ${p.views}</span><span>❤ ${p.likes}</span><span>💬 ${p.commentCount}</span></div>
      </div></div>`;
  }).join('');
  $$('#list .post').forEach(el=>el.onclick=()=>openDetail(+el.dataset.id));
  renderPager(res.page,res.totalPages);
}
function renderPager(cur,total){
  if(total<=1){$('#pager').innerHTML='';return;}
  $('#pager').innerHTML=`<button id="prevPage" ${cur<=0?'disabled':''}>← 이전</button>
    <span class="pinfo">${cur+1} / ${total}</span>
    <button id="nextPage" ${cur>=total-1?'disabled':''}>다음 →</button>`;
  $('#prevPage').onclick=()=>{if(page>0){page--;renderList();toBoard();}};
  $('#nextPage').onclick=()=>{if(page<total-1){page++;renderList();toBoard();}};
}

/* ---------- 상세 ---------- */
function mediaHTML(url){
  if(!url)return '';
  if(isImg(url))return `<img class="media" src="${esc(url)}" onerror="this.style.display='none'">`;
  if(isVid(url))return `<video class="media" src="${esc(url)}" controls></video>`;
  const yt=url.match(/(?:youtube\.com\/watch\?v=|youtube\.com\/shorts\/|youtu\.be\/)([\w-]+)/);
  if(yt)return `<iframe class="media" style="width:100%;aspect-ratio:16/9;height:auto;border:0" src="https://www.youtube.com/embed/${yt[1]}" allowfullscreen></iframe>`;
  return `<div class="note">🔗 <a href="${esc(url)}" target="_blank" style="color:var(--blue)">${esc(url)}</a></div>`;
}
async function openDetail(id){
  let p;try{p=await data.get(id);}catch(e){bannerError('글을 불러오지 못했어요: '+e.message);return;}
  const c=catName(p.category);
  $('#detailView').innerHTML=`
    <button class="btn sec sm" id="backBtn">← 목록</button>
    <div style="margin-top:12px" class="badge">${c.e} ${c.n}</div>
    <h2>${esc(p.title)}</h2>
    <div class="meta">👤 ${esc(p.author)} · 🕒 ${fmt(p.createdAt)} · 👁 ${p.views}</div>
    ${mediaHTML(p.media)}
    <div class="md" id="mdBody">${renderMarkdown(p.content)}</div>
    <div class="likebar">
      <button class="btn sm" id="likeBtn">❤ 좋아요 ${p.likes}</button>
      <button class="btn sec sm" id="editBtn">✏️ 수정</button>
      <button class="btn sec sm" id="delBtn">🗑 삭제</button>
    </div>
    <div class="comments">
      <h2 style="font-size:15px;margin-bottom:10px">💬 댓글 <span id="cCount">${p.comments.length}</span></h2>
      <div id="commentList"></div>
      <div class="toolbar" style="margin-top:10px">
        <input id="cWho" placeholder="닉네임" style="max-width:120px">
        <input id="cText" placeholder="댓글을 입력하세요">
        <button class="btn sm" id="cAdd">등록</button>
      </div>
    </div>`;
  $('#mdBody').querySelectorAll('pre code').forEach(b=>hljs.highlightElement(b));
  renderComments(p.comments);
  showView('detailView');
  setPostUrl(id);
  $('#backBtn').onclick=showList;
  $('#likeBtn').onclick=async()=>{try{const r=await data.like(id);$('#likeBtn').textContent='❤ 좋아요 '+r.likes;}catch(e){alert(e.message);}};
  $('#editBtn').onclick=()=>showEdit(p);
  $('#delBtn').onclick=async()=>{
    const pw=prompt('글 비밀번호를 입력하세요');if(pw===null)return;
    try{await data.remove(id,pw);showList();}catch(e){alert(e.message);}
  };
  $('#cAdd').onclick=async()=>{
    const author=($('#cWho').value||'익명').trim(),content=$('#cText').value.trim();if(!content)return;
    try{await data.comment(id,{author,content});const np=await data.get(id);
      renderComments(np.comments);$('#cCount').textContent=np.comments.length;$('#cText').value='';
    }catch(e){alert(e.message);}
  };
}
function renderComments(list){
  $('#commentList').innerHTML=(list&&list.length)?list.map(c=>
    `<div class="comment"><span class="who">${esc(c.author)}</span><span class="ct">${fmt(c.createdAt)}</span>
     <div class="cx">${esc(c.content)}</div></div>`).join(''):'<div class="note">첫 댓글을 남겨보세요.</div>';
}

/* ---------- 글쓰기 / 수정 ---------- */
let editingId=null;
function showWrite(){
  editingId=null;
  $('#writeTitle').textContent='✏️ 새 글 쓰기';
  $('#wCat').innerHTML=CATS.map(c=>`<option value="${c.k}" ${filter===c.k?'selected':''}>${c.e} ${c.n}</option>`).join('');
  $('#wCat').disabled=false;
  ['wTitle','wAuthor','wMedia','wContent','wPassword','wAdmin'].forEach(id=>$('#'+id).value='');
  $('#wFile').value='';$('#uploadState').textContent='';
  $('#authorLabel').style.display='';$('#wAuthor').style.display='';
  $('#adminRow').classList.add('hidden');
  $('#submitBtn').textContent='등록';
  showView('writeView');
}
function showEdit(p){
  editingId=p.id;
  $('#writeTitle').textContent='✏️ 글 수정';
  $('#wCat').innerHTML=CATS.map(c=>`<option value="${c.k}" ${p.category===c.k?'selected':''}>${c.e} ${c.n}</option>`).join('');
  $('#wCat').disabled=true;
  $('#wTitle').value=p.title;
  $('#wAuthor').value=p.author;$('#authorLabel').style.display='none';$('#wAuthor').style.display='none';
  $('#adminRow').classList.add('hidden');
  $('#wMedia').value=p.media||'';$('#wContent').value=p.content;
  $('#wFile').value='';$('#uploadState').textContent='';$('#wPassword').value='';
  $('#submitBtn').textContent='수정 완료';
  showView('writeView');
}
// 목록·상세·글쓰기 중 하나만 보인다. 상세·글쓰기 동안엔 페이지 소개를 접고(body.reading) 맨 위로
function showView(id){
  ['listView','detailView','writeView'].forEach(v=>$('#'+v).classList.toggle('hidden',v!==id));
  document.body.classList.toggle('reading',id!=='listView');
  if(id!=='listView')window.scrollTo(0,0);
}
// 게시판 머리가 화면 위로 지나갔으면 게시판으로 돌아온다 (always: 늘)
function toBoard(always){const b=$('#board');if(always||b.getBoundingClientRect().top<0)b.scrollIntoView();}
async function maybeUpload(){
  const f=$('#wFile').files[0];if(!f)return null;
  $('#uploadState').textContent='⏳ 업로드 중...';
  const url=await data.upload(f);
  $('#uploadState').textContent='✅ 업로드 완료';
  return url;
}
async function submitPost(){
  const title=$('#wTitle').value.trim(),content=$('#wContent').value.trim(),password=$('#wPassword').value;
  if(!title){alert('제목을 입력하세요');return;}
  if(!content){alert('내용을 입력하세요');return;}
  if(!password){alert('비밀번호를 입력하세요'+(editingId?' (작성 시 입력한 값)':' (수정/삭제에 필요)'));return;}
  $('#submitBtn').disabled=true;
  try{
    let media=$('#wMedia').value.trim();
    const uploaded=await maybeUpload();if(uploaded)media=uploaded;
    if(editingId){
      await data.update(editingId,{title,content,media,password});
      const np=await data.get(editingId);openDetail(np.id);
    }else{
      await data.create({category:$('#wCat').value,title,author:$('#wAuthor').value.trim(),content,media,password,
        adminPassword:$('#wAdmin').value});
      page=0;showList();
    }
  }catch(e){alert((editingId?'수정':'등록')+' 실패: '+e.message);}
  finally{$('#submitBtn').disabled=false;}
}
function setPostUrl(id){try{const u=new URL(location.href);if(id)u.searchParams.set('post',id);else u.searchParams.delete('post');history.replaceState(null,'',u);}catch(e){}}
function showList(){
  setPostUrl(null);
  showView('listView');renderCats();renderList();toBoard(true);
}

/* ---------- 이벤트 ---------- */
$('#writeBtn').onclick=showWrite;
$('#submitBtn').onclick=submitPost;
$('#cancelBtn').onclick=showList;
let searchTimer;$('#search').addEventListener('input',()=>{clearTimeout(searchTimer);searchTimer=setTimeout(()=>{page=0;renderList();},250);});
$('#wFile').addEventListener('change',()=>{const f=$('#wFile').files[0];$('#uploadState').textContent=f?('선택됨: '+f.name):'';});
$('#wAuthor').addEventListener('input',()=>{$('#adminRow').classList.toggle('hidden',!isReservedName($('#wAuthor').value));});

/* ---------- 초기 ---------- */
showBanner();renderCats();renderList();
{const pid=+new URLSearchParams(location.search).get('post');if(pid)openDetail(pid);} // ?post=번호 로 들어오면 그 글부터
})();

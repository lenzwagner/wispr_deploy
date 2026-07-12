import os
import shutil
import tempfile
import re
from typing import Optional
from fastapi import FastAPI, UploadFile, File, Form, HTTPException
from fastapi.middleware.cors import CORSMiddleware
from pydantic import BaseModel
from openai import OpenAI
from anthropic import Anthropic
from dotenv import load_dotenv

# Load env variables from .env file
load_dotenv()

app = FastAPI(title="Wispr Flow Clone API", version="1.0.0")

# Enable CORS for local testing
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

class TranscribeResponse(BaseModel):
    raw_text: str
    polished_text: str
    app_name: Optional[str] = None

# --- CONFIGURATION (MODELS & KEYS) ---
groq_api_key = os.environ.get("GROQ_API_KEY")
openai_api_key = os.environ.get("OPENAI_API_KEY")
anthropic_api_key = os.environ.get("ANTHROPIC_API_KEY")
nvidia_api_key = os.environ.get("NVIDIA_API_KEY")

# Model configurations with default fallbacks
GROQ_LLM_MODEL = os.environ.get("GROQ_LLM_MODEL", "llama-3.3-70b-versatile")
OPENAI_LLM_MODEL = os.environ.get("OPENAI_LLM_MODEL", "gpt-4o-mini")
ANTHROPIC_LLM_MODEL = os.environ.get("ANTHROPIC_LLM_MODEL", "claude-3-5-haiku-20241022")
NVIDIA_LLM_MODEL = os.environ.get("NVIDIA_LLM_MODEL", "meta/llama-3.3-70b-instruct")

GROQ_WHISPER_MODEL = os.environ.get("GROQ_WHISPER_MODEL", "whisper-large-v3")
NVIDIA_WHISPER_MODEL = os.environ.get("NVIDIA_WHISPER_MODEL", "openai/whisper-large-v3")
OPENAI_WHISPER_MODEL = os.environ.get("OPENAI_WHISPER_MODEL", "whisper-1")

# Instantiate global clients (only if keys are present) to enable connection reuse
groq_client = None
if groq_api_key:
    try:
        groq_client = OpenAI(api_key=groq_api_key, base_url="https://api.groq.com/openai/v1")
    except Exception as e:
        print(f"Error initializing Groq client: {e}")

openai_client = None
if openai_api_key:
    try:
        openai_client = OpenAI(api_key=openai_api_key)
    except Exception as e:
        print(f"Error initializing OpenAI client: {e}")

anthropic_client = None
if anthropic_api_key:
    try:
        anthropic_client = Anthropic(api_key=anthropic_api_key)
    except Exception as e:
        print(f"Error initializing Anthropic client: {e}")

nvidia_client = None
if nvidia_api_key:
    try:
        nvidia_client = OpenAI(api_key=nvidia_api_key, base_url="https://integrate.api.nvidia.com/v1")
    except Exception as e:
        print(f"Error initializing NVIDIA client: {e}")

# --- PROMPTS & REGEX ---

# System prompt with Markdown strictness and Few-Shot Examples
BASE_SYSTEM_PROMPT = """Du bist das Text-Optimierungs-Modul einer erstklassigen Diktier-App (ähnlich wie WisprFlow).
Deine EINZIGE Aufgabe ist es, das rohe, gesprochene Transkript in perfekten, lesfertigen Text zu verwandeln.

WICHTIGSTE REGEL: Der Text muss absolut natürlich klingen und genau dem entsprechen, was der Nutzer gesagt hat – nur ohne Fehler, Füllwörter und Stottern. Füge NIEMALS Meta-Text, Bestätigungen oder Erklärungen hinzu.

KERN-REGELN:
1. FÜLLWÖRTER & KORREKTUREN: Entferne restlos alle Füllwörter (ähm, ah, öh, halt, sozusagen, ja). Löse Selbstkorrekturen auf (z.B. "am Dienstag... ah nein, Mittwoch" -> "am Mittwoch").
2. DIKTIERBEFEHLE EXAKT UMSETZEN: 
   - "Punkt" -> .
   - "Komma" -> ,
   - "Fragezeichen" -> ?
   - "Ausrufezeichen" -> !
   - Behalte Zeilenumbrüche (\n oder \n\n) strikt bei.
3. FORMATIERUNG VON LISTEN (SEHR WICHTIG):
   - Wenn der Nutzer eine Aufzählung diktiert (z.B. durch Worte wie "Spiegelstrich", "Stichpunkt", "erstens", "zweitens" oder durch implizites Aufzählen von Dingen wie "A, B und C"), formatiere diese ZWINGEND als saubere **Markdown-Liste**.
   - Bei impliziten Aufzählungen in Fließtexten (z.B. "Ich brauche Äpfel, Birnen und Bananen" oder "Wir müssen einkaufen, putzen und kochen") formatiere den Text zwingend so, dass ein einleitender Satz mit Doppelpunkt entsteht und die Elemente als Aufzählungspunkte (- ) darunter stehen.
   - Verwende `- ` für unnummerierte Listen und `1. `, `2. ` etc. für nummerierte Listen.
   - Nach dem einleitenden Satz oder Doppelpunkt MUSS zwingend eine Leerzeile (Doppelabsatz / \n\n) stehen, bevor der erste Listenpunkt beginnt.
   - Trenne Listen IMMER durch eine Leerzeile (\n\n) vom restlichen Text ab (sowohl davor als auch danach).
   - Jeder Listenpunkt beginnt mit einem Großbuchstaben.
4. ZAHLEN ALS ZIFFERN (SEHR WICHTIG):
   - Schreibe alle gesprochenen Zahlen (z.B. "zwei", "drei", "vier", "zehn", "hundert" etc.), die Mengen, Stückzahlen, Werte oder Uhrzeiten angeben, ZWINGEND als Ziffern ("2", "3", "4", "10", "100" etc.).
   - Dies gilt ausnahmslos auch am Anfang von Listenpunkten oder Sätzen.
   - Unbestimmte Artikel ("ein", "eine") können als solche beibehalten werden, es sei denn, sie bezeichnen klar die Anzahl 1.
5. GRAMMATIK & SINN: Korrigiere Grammatik- und Rechtschreibfehler perfekt. Verändere NIEMALS den inhaltlichen Kern oder die Wortwahl (außer zur Fehlerbehebung). Keine stilistischen "Verschönerungen".
6. STRIKTES OUTPUT-FORMAT: Gib AUSSCHLIESSLICH den finalen, optimierten Text zurück. Keine Einleitung, kein "Hier ist der Text:".

BEISPIELE FÜR DIE KORREKTUR (FEW-SHOT):

Input: "Hallo Herr Müller Komma \n\n ich äh wollte fragen ob wir das Meeting auf Dienstag... ah nee auf Mittwoch verschieben können Punkt"
Output: "Hallo Herr Müller,\n\nich wollte fragen, ob wir das Meeting auf Mittwoch verschieben können."

Input: "Schreib eine kurze Liste Punkt \n- erstens Milch \n- zweitens Eier \n- drittens Brot Punkt"
Output: "Schreib eine kurze Liste.\n\n- Milch\n- Eier\n- Brot."

Input: "Ich brauche zwei Äpfel Komma drei Birnen und zwei Bananen Punkt"
Output: "Ich brauche:\n\n- 2 Äpfel\n- 3 Birnen\n- 2 Bananen."

Input: "Wir müssen heute noch einkaufen gehen Komma das Auto waschen und die Wäsche machen Punkt"
Output: "Wir müssen heute noch:\n\n- Einkaufen gehen\n- Das Auto waschen\n- Die Wäsche machen."

Input: "Wir müssen folgende Dinge tun Doppelpunkt \n- Punkt eins das Design fertigstellen \n- Punkt zwei den Code hochladen und \n- Punkt drei die Tests schreiben Punkt"
Output: "Wir müssen folgende Dinge tun:\n\n1. Das Design fertigstellen\n2. Den Code hochladen\n3. Die Tests schreiben."

Input: "Das war ein richtig richtig äh geiles Projekt Ausrufezeichen"
Output: "Das war ein richtig, richtig geiles Projekt!"
"""

def get_app_specific_prompt(app_name: Optional[str]) -> str:
    if not app_name:
        return BASE_SYSTEM_PROMPT
    
    app_lower = app_name.lower()
    
    # 1. Entwickler-Tools & Terminals (VS Code, Cursor, Xcode, Android Studio, Terminal, Git)
    if any(x in app_lower for x in ["terminal", "iterm", "xcode", "studio", "vscode", "visual studio", "cursor", "intellij", "pycharm", "idea", "sublime", "git", "github"]):
        style_instruction = """
\nKONTEXT: Der Nutzer diktiert in einem Programmier-Editor, Terminal oder Entwickler-Tool.
REGELN FÜR DIESEN KONTEXT:
- Halte die Formatierung absolut minimalistisch.
- Verwende KEINE automatischen Markdown-Auszeichnungen wie fett (**), kursiv (*) oder Überschriften (#), es sei denn, der Nutzer verlangt dies explizit.
- Schreibe Variablen, Funktionsnamen oder Befehle exakt so, wie sie für Programmierer typisch sind (z.B. camelCase, snake_case, kebab-case oder CLI-Argumente).
- Füge niemals automatische Begrüßungen oder Signaturen hinzu.
"""
    
    # 2. Browser & Suchleisten (Chrome, Safari, Firefox, Edge, Opera, Search)
    elif any(x in app_lower for x in ["chrome", "safari", "firefox", "edge", "opera", "browser", "search", "spotify"]):
        style_instruction = """
\nKONTEXT: Der Nutzer diktiert in einem Web-Browser, einer Suchleiste oder einem einfachen Eingabefeld.
REGELN FÜR DIESEN KONTEXT:
- Formatiere den Text extrem flach und kompakt in einer einzigen Zeile oder einfachen Sätzen.
- Nutze ZWINGEND KEIN Markdown (keine Bindestriche für Listen, kein fettgedruckter Text, keine Überschriften). In Suchfeldern wird Markdown nicht gerendert und wirkt störend.
- Vermeide Zeilenumbrüche, es sei denn, sie wurden explizit diktiert.
"""

    # 3. Slack, MS Teams, Discord, Mattermost, Skype (Business-Chat)
    elif any(x in app_lower for x in ["slack", "teams", "discord", "chat", "mattermost", "skype", "webex"]):
        style_instruction = """
\nKONTEXT: Der Nutzer diktiert eine Nachricht für einen Business-Chat (z.B. Slack/Teams).
REGELN FÜR DIESEN KONTEXT:
- Halte die Struktur klar, übersichtlich und empfängerfreundlich.
- Nutze Absätze (\n\n) bei Themenwechseln.
- Wenn eine Grußformel oder Anrede existiert, setze sie in eine neue Zeile.
- Markdown-Listen (- ) sind bei Aufzählungen erwünscht.
"""

    # 4. WhatsApp, Signal, Telegram, iMessage/Messages, Facebook Messenger (Private Chats)
    elif any(x in app_lower for x in ["whatsapp", "signal", "telegram", "message", "imessage", "messenger", "orca", "securesms", "messaging", "sms"]):
        style_instruction = """
\nKONTEXT: Der Nutzer diktiert eine private Chat-Nachricht (z.B. WhatsApp/Signal).
REGELN FÜR DIESEN KONTEXT:
- Behalte den lockeren, informellen und natürlichen Charakter des Sprechers bei.
- Nutze Emojis nur, wenn der Nutzer sie explizit diktiert (z.B. "Smiley", "Zwinker-Smiley").
- Keine übermäßige formelle Strukturierung (z.B. keine starren E-Mail-Strukturen).
"""

    # 5. Mail-Apps & Dokumenten-Editoren (Gmail/GM, Outlook, Mail, Notes, Word, Pages, Docs, Keep)
    elif any(x in app_lower for x in ["mail", "outlook", "gmail", "notes", "word", "textedit", "pages", "keep", "docs", "document", "editor", "gm", "evernote"]):
        style_instruction = """
\nKONTEXT: Der Nutzer diktiert eine E-Mail oder ein offizielles Dokument. 
WENDE ZWINGEND DIESE DOKUMENT- UND E-MAIL-FORMATIERUNGSREGELN AN:
- Setze die Anrede (z.B. "Hallo Herr X,", "Sehr geehrte Frau Y,") IMMER in eine eigene Zeile, gefolgt von einer leeren Zeile (Doppelabsatz / \n\n).
- Der erste Satz nach der Anrede beginnt im Deutschen zwingend kleingeschrieben (außer das erste Wort ist ein Nomen).
- Setze die Grußformel am Ende (z.B. "Mit freundlichen Grüßen", "Liebe Grüße", "Viele Grüße") IMMER in eine eigene Zeile, mit einer leeren Zeile davor (\n\n).
- Wenn nach der Grußformel ein Name diktiert wird, setze diesen direkt in die nächste Zeile darunter (\n).
- Formatiere Aufzählungen in Mail-Texten immer sauber als Markdown mit Bullet-Points (- ).
"""
    
    else:
        style_instruction = f"\nKONTEXT: Der Nutzer diktiert in der App '{app_name}'. Passe die Formatierung an die typischen Konventionen dieser App an (z.B. flacher Text für Suchen, strukturierter Text für Dokumente)."
        
    return BASE_SYSTEM_PROMPT + style_instruction

def pre_process_transcript(raw_text: str) -> str:
    """
    Ersetzt strukturelle Diktierbefehle durch harte Markdown-Zeichen, 
    bevor das LLM den Text sieht. Verhindert das Ausschreiben von Befehlen.
    """
    # Absätze und Zeilenumbrüche
    text = re.sub(r'(?i)\b(neuer absatz)\b', '\n\n', raw_text)
    text = re.sub(r'(?i)\b(neue zeile|zeilenumbruch)\b', '\n', text)
    
    # Unnummerierte Listenbefehle (Spiegelstriche, Stichpunkte, Bullets)
    text = re.sub(
        r'(?i)\b(spiegelstrich|spiegelstriche|listenpunkt|listenpunkte|stichpunkt|stichpunkte|aufzählungspunkt|aufzählungspunkte|neuer punkt|bullet\s*point|bullet\s*points)\b', 
        '\n- ', 
        text
    )
    
    # Nummerierte Listenbefehle (erstens, zweitens, ...) am Satzanfang/nach Umbruch durch "1. ", "2. " ersetzen
    # Dies hilft dem LLM, die Struktur sofort als nummerierte Liste zu erkennen
    text = re.sub(r'(?i)\b(erstens|punkt eins)\b', '\n1. ', text)
    text = re.sub(r'(?i)\b(zweitens|punkt zwei)\b', '\n2. ', text)
    text = re.sub(r'(?i)\b(drittens|punkt drei)\b', '\n3. ', text)
    text = re.sub(r'(?i)\b(viertens|punkt vier)\b', '\n4. ', text)
    text = re.sub(r'(?i)\b(fünftens|punkt fünf)\b', '\n5. ', text)
    
    return text

# --- API ENDPOINTS ---

@app.get("/")
def health_check():
    return {
        "status": "healthy",
        "providers": {
            "groq": groq_api_key is not None,
            "openai": openai_api_key is not None,
            "anthropic": anthropic_api_key is not None,
            "nvidia": nvidia_api_key is not None
        }
    }

# Declared as synchronous (def) so FastAPI executes it in an external thread pool,
# preventing blocking of the main async event loop during disk/network I/O.
@app.post("/transcribe", response_model=TranscribeResponse)
def transcribe_audio(
    file: UploadFile = File(...),
    app_name: Optional[str] = Form(None),
    context: Optional[str] = Form(None)
):
    print("--- Transcribe Request ---")
    print(f"App: {app_name}")
    if context:
        print(f"Context captured (length: {len(context)})")

    if not groq_api_key and not openai_api_key and not nvidia_api_key:
        raise HTTPException(
            status_code=500,
            detail="Config Error: None of the keys GROQ_API_KEY, OPENAI_API_KEY, or NVIDIA_API_KEY are configured in the environment."
        )

    suffix = os.path.splitext(file.filename)[1] if file.filename else ".wav"
    with tempfile.NamedTemporaryFile(delete=False, suffix=suffix) as temp_audio:
        shutil.copyfileobj(file.file, temp_audio)
        temp_audio_path = temp_audio.name

    try:
        raw_text = ""
        
        # 1. Transcribe Audio
        if groq_client and not raw_text:
            try:
                print("Attempting Groq transcription...")
                with open(temp_audio_path, "rb") as audio_file:
                    translation = groq_client.audio.transcriptions.create(
                        model=GROQ_WHISPER_MODEL,
                        file=audio_file
                    )
                    raw_text = translation.text
            except Exception as e:
                print(f"Groq transcription failed: {e}")

        if nvidia_client and not raw_text:
            try:
                print("Attempting NVIDIA transcription...")
                with open(temp_audio_path, "rb") as audio_file:
                    translation = nvidia_client.audio.transcriptions.create(
                        model=NVIDIA_WHISPER_MODEL,
                        file=audio_file
                    )
                    raw_text = translation.text
            except Exception as e:
                print(f"NVIDIA transcription failed: {e}")

        if openai_client and not raw_text:
            try:
                print("Attempting OpenAI transcription...")
                with open(temp_audio_path, "rb") as audio_file:
                    translation = openai_client.audio.transcriptions.create(
                        model=OPENAI_WHISPER_MODEL,
                        file=audio_file
                    )
                    raw_text = translation.text
            except Exception as e:
                print(f"OpenAI transcription failed: {e}")

        if not raw_text:
            raise HTTPException(status_code=500, detail="Transcription resulted in empty text or failed on all providers.")

        # 2. Pre-Process Text (Regex) - Fixes structural commands
        pre_processed_text = pre_process_transcript(raw_text)

        # 3. Polish text using LLM
        polished_text = ""
        system_prompt = get_app_specific_prompt(app_name)

        user_content = f"Hier ist das Transkript zum Optimieren:\n\n{pre_processed_text}"
        if context:
            user_content = f"KONTEXT VOM BILDSCHIRM (Nutze dies für korrekte Namen, Fachbegriffe und Stil):\n{context}\n\n---\n\nTRANSKRIPT ZUM OPTIMIEREN:\n{pre_processed_text}"

        if anthropic_client:
            try:
                message = anthropic_client.messages.create(
                    model=ANTHROPIC_LLM_MODEL,
                    max_tokens=1024,
                    temperature=0.0,
                    system=system_prompt,
                    messages=[
                        {"role": "user", "content": user_content}
                    ]
                )
                polished_text = message.content[0].text.strip()
            except Exception as e:
                print(f"Anthropic polishing failed: {e}")

        if not polished_text and openai_client:
            try:
                completion = openai_client.chat.completions.create(
                    model=OPENAI_LLM_MODEL,
                    messages=[
                        {"role": "system", "content": system_prompt},
                        {"role": "user", "content": user_content}
                    ],
                    temperature=0.0
                )
                polished_text = completion.choices[0].message.content.strip()
            except Exception as e:
                print(f"OpenAI polishing failed: {e}")

        if not polished_text and nvidia_client:
            try:
                extra_args = {"extra_body": {"chat_template_kwargs": {"thinking": False}}} if "deepseek" in NVIDIA_LLM_MODEL.lower() else {}
                
                completion = nvidia_client.chat.completions.create(
                    model=NVIDIA_LLM_MODEL,
                    messages=[
                        {"role": "system", "content": system_prompt},
                        {"role": "user", "content": user_content}
                    ],
                    temperature=0.0,
                    **extra_args
                )
                polished_text = completion.choices[0].message.content.strip()
            except Exception as e:
                print(f"NVIDIA polishing failed: {e}")

        if not polished_text and groq_client:
            try:
                completion = groq_client.chat.completions.create(
                    model=GROQ_LLM_MODEL,
                    messages=[
                        {"role": "system", "content": system_prompt},
                        {"role": "user", "content": user_content}
                    ],
                    temperature=0.0
                )
                polished_text = completion.choices[0].message.content.strip()
            except Exception as e:
                print(f"Groq polishing failed: {e}")

        if not polished_text:
            raise HTTPException(status_code=500, detail="Polishing step failed to return text on all providers.")

        return TranscribeResponse(
            raw_text=raw_text, 
            polished_text=polished_text,
            app_name=app_name
        )

    finally:
        try:
            os.unlink(temp_audio_path)
        except Exception:
            pass

if __name__ == "__main__":
    import uvicorn
    port = int(os.environ.get("PORT", 8000))
    uvicorn.run("main:app", host="0.0.0.0", port=port, reload=True)
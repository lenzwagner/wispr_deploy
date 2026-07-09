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
   - Wenn der Nutzer eine Aufzählung diktiert (z.B. durch Worte wie "Spiegelstrich", "Stichpunkt", "erstens", "zweitens" oder durch implizites Aufzählen von Dingen), formatiere diese ZWINGEND als saubere **Markdown-Liste**.
   - Verwende `- ` für unnummerierte Listen und `1. `, `2. ` etc. für nummerierte Listen.
   - Trenne Listen IMMER durch eine Leerzeile (\n\n) vom restlichen Text ab (sowohl davor als auch danach).
   - Jeder Listenpunkt beginnt mit einem Großbuchstaben.
4. GRAMMATIK & SINN: Korrigiere Grammatik- und Rechtschreibfehler perfekt. Verändere NIEMALS den inhaltlichen Kern oder die Wortwahl (außer zur Fehlerbehebung). Keine stilistischen "Verschönerungen".
5. STRIKTES OUTPUT-FORMAT: Gib AUSSCHLIESSLICH den finalen, optimierten Text zurück. Keine Einleitung, kein "Hier ist der Text:".

BEISPIELE FÜR DIE KORREKTUR (FEW-SHOT):

Input: "Hallo Herr Müller Komma \n\n ich äh wollte fragen ob wir das Meeting auf Dienstag... ah nee auf Mittwoch verschieben können Punkt"
Output: "Hallo Herr Müller,\n\nich wollte fragen, ob wir das Meeting auf Mittwoch verschieben können."

Input: "Schreib eine kurze Liste Punkt \n- erstens Milch \n- zweitens Eier \n- drittens Brot Punkt"
Output: "Schreib eine kurze Liste.\n\n- Milch\n- Eier\n- Brot."

Input: "Wir müssen folgende Dinge tun Doppelpunkt \n- Punkt eins das Design fertigstellen \n- Punkt zwei den Code hochladen und \n- Punkt drei die Tests schreiben Punkt"
Output: "Wir müssen folgende Dinge tun:\n\n1. Das Design fertigstellen\n2. Den Code hochladen\n3. Die Tests schreiben."

Input: "Das war ein richtig richtig äh geiles Projekt Ausrufezeichen"
Output: "Das war ein richtig, richtig geiles Projekt!"
"""

def get_app_specific_prompt(app_name: Optional[str]) -> str:
    if not app_name:
        return BASE_SYSTEM_PROMPT
    
    app_lower = app_name.lower()
    
    # Slack, MS Teams, Discord, etc.
    if any(x in app_lower for x in ["slack", "teams", "discord", "chat"]):
        style_instruction = """
\nKONTEXT: Der Nutzer diktiert eine Nachricht für einen Business-Chat (z.B. Slack/Teams).
REGELN FÜR DIESEN KONTEXT:
- Halte die Struktur klar und übersichtlich.
- Nutze Absätze (\n\n) bei Themenwechseln.
- Wenn eine Grußformel existiert, setze sie in eine neue Zeile.
"""
    # WhatsApp, Signal, iMessage, etc.
    elif any(x in app_lower for x in ["whatsapp", "signal", "telegram", "message", "imessage"]):
        style_instruction = """
\nKONTEXT: Der Nutzer diktiert eine private Chat-Nachricht.
REGELN FÜR DIESEN KONTEXT:
- Behalte den lockeren, natürlichen Charakter bei.
- Nutze Emojis nur, wenn der Nutzer sie explizit diktiert (z.B. "Smiley").
"""
    # Mail apps, Outlook, Notes, text editors
    elif any(x in app_lower for x in ["mail", "outlook", "gmail", "notes", "word", "textedit", "pages"]):
        style_instruction = """
\nKONTEXT: Der Nutzer diktiert eine E-Mail oder ein Dokument. 
WENDE ZWINGEND DIESE E-MAIL-FORMATIERUNGSREGELN AN:
- Setze die Anrede (z.B. "Hallo Herr X,", "Sehr geehrte Frau Y,") IMMER in eine eigene Zeile, gefolgt von einer leeren Zeile (Doppelabsatz / \n\n).
- Der erste Satz nach der Anrede beginnt im Deutschen zwingend kleingeschrieben (außer das erste Wort ist ein Nomen).
- Setze die Grußformel am Ende (z.B. "Mit freundlichen Grüßen", "Liebe Grüße", "Viele Grüße") IMMER in eine eigene Zeile, mit einer leeren Zeile davor (\n\n).
- Wenn nach der Grußformel ein Name diktiert wird, setze diesen direkt in die nächste Zeile darunter (\n).
- Formatiere Aufzählungen in Mail-Texten immer sauber als Markdown mit Bullet-Points (- ).
"""
    else:
        style_instruction = f"\nKONTEXT: Der Nutzer diktiert in der App '{app_name}'. Passe die Formatierung an die typischen Konventionen dieser App an."
        
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
            "groq": "GROQ_API_KEY" in os.environ,
            "openai": "OPENAI_API_KEY" in os.environ,
            "anthropic": "ANTHROPIC_API_KEY" in os.environ,
            "nvidia": "NVIDIA_API_KEY" in os.environ
        }
    }

@app.post("/transcribe", response_model=TranscribeResponse)
async def transcribe_audio(
    file: UploadFile = File(...),
    app_name: Optional[str] = Form(None)
):
    groq_api_key = os.environ.get("GROQ_API_KEY")
    openai_api_key = os.environ.get("OPENAI_API_KEY")
    anthropic_api_key = os.environ.get("ANTHROPIC_API_KEY")
    nvidia_api_key = os.environ.get("NVIDIA_API_KEY")

    print("--- Transcribe Request ---")
    print(f"Detected Keys: GROQ_API_KEY={'set' if groq_api_key else 'MISSING'}, NVIDIA_API_KEY={'set' if nvidia_api_key else 'MISSING'}, OPENAI_API_KEY={'set' if openai_api_key else 'MISSING'}, ANTHROPIC_API_KEY={'set' if anthropic_api_key else 'MISSING'}")

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
        if groq_api_key and not raw_text:
            try:
                print("Attempting Groq transcription...")
                groq_client = OpenAI(api_key=groq_api_key, base_url="https://api.groq.com/openai/v1")
                with open(temp_audio_path, "rb") as audio_file:
                    translation = groq_client.audio.transcriptions.create(
                        model="whisper-large-v3",
                        file=audio_file
                    )
                    raw_text = translation.text
            except Exception as e:
                print(f"Groq transcription failed: {e}")

        if nvidia_api_key and not raw_text:
            try:
                print("Attempting NVIDIA transcription...")
                nvidia_client = OpenAI(api_key=nvidia_api_key, base_url="https://integrate.api.nvidia.com/v1")
                model_name = os.environ.get("NVIDIA_WHISPER_MODEL", "openai/whisper-large-v3")
                with open(temp_audio_path, "rb") as audio_file:
                    translation = nvidia_client.audio.transcriptions.create(
                        model=model_name,
                        file=audio_file
                    )
                    raw_text = translation.text
            except Exception as e:
                print(f"NVIDIA transcription failed: {e}")

        if openai_api_key and not raw_text:
            try:
                print("Attempting OpenAI transcription...")
                openai_client = OpenAI(api_key=openai_api_key)
                with open(temp_audio_path, "rb") as audio_file:
                    translation = openai_client.audio.transcriptions.create(
                        model="whisper-1",
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

        if anthropic_api_key:
            try:
                anthropic_client = Anthropic(api_key=anthropic_api_key)
                message = anthropic_client.messages.create(
                    model="claude-3-5-haiku-20241022",
                    max_tokens=1024,
                    temperature=0.0,
                    system=system_prompt,
                    messages=[
                        {"role": "user", "content": f"Hier ist das Transkript zum Optimieren:\n\n{pre_processed_text}"}
                    ]
                )
                polished_text = message.content[0].text.strip()
            except Exception as e:
                print(f"Anthropic polishing failed: {e}")

        if not polished_text and openai_api_key:
            try:
                openai_client = OpenAI(api_key=openai_api_key)
                completion = openai_client.chat.completions.create(
                    model="gpt-4o-mini",
                    messages=[
                        {"role": "system", "content": system_prompt},
                        {"role": "user", "content": f"Hier ist das Transkript zum Optimieren:\n\n{pre_processed_text}"}
                    ],
                    temperature=0.0
                )
                polished_text = completion.choices[0].message.content.strip()
            except Exception as e:
                print(f"OpenAI polishing failed: {e}")

        if not polished_text and nvidia_api_key:
            try:
                nvidia_client = OpenAI(api_key=nvidia_api_key, base_url="https://integrate.api.nvidia.com/v1")
                model_name = os.environ.get("NVIDIA_LLM_MODEL", "meta/llama-3.3-70b-instruct")
                extra_args = {"extra_body": {"chat_template_kwargs": {"thinking": False}}} if "deepseek" in model_name.lower() else {}
                
                completion = nvidia_client.chat.completions.create(
                    model=model_name,
                    messages=[
                        {"role": "system", "content": system_prompt},
                        {"role": "user", "content": f"Hier ist das Transkript zum Optimieren:\n\n{pre_processed_text}"}
                    ],
                    temperature=0.0,
                    **extra_args
                )
                polished_text = completion.choices[0].message.content.strip()
            except Exception as e:
                print(f"NVIDIA polishing failed: {e}")

        if not polished_text and groq_api_key:
            try:
                groq_client = OpenAI(api_key=groq_api_key, base_url="https://api.groq.com/openai/v1")
                completion = groq_client.chat.completions.create(
                    model="llama-3.3-70b-versatile",
                    messages=[
                        {"role": "system", "content": system_prompt},
                        {"role": "user", "content": f"Hier ist das Transkript zum Optimieren:\n\n{pre_processed_text}"}
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
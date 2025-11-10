### Overview
Add strategic coaching capabilities to AmbientAI that help users define, track, and achieve long-term goals through voice-first interaction.
**Core principle**: One coach with multiple conversation modes, not separate bots per function.
### Goal Hierarchy (Entities)
#### Vision
**Purpose**: 10+ year aspirational statement anchoring all goals to fundamental purpose.  
**Personal meaning**: Where you want to be, the impact you want to have made.
```kotlin
@Entity
data class Vision(
    @Id var id: Long = 0,
    var text: String,                // "Build technology that amplifies human potential"
    var createdAt: Long,
    var lastReviewedAt: Long
) {
    lateinit var missions: ToMany<Mission>
}
```
#### Mission
**Purpose**: 1-3 year concrete pursuit translating vision into measurable direction.  
**Personal meaning**: What you'll accomplish to move toward your vision.
```kotlin
@Entity
data class Mission(
    @Id var id: Long = 0,
    var text: String,                // "Launch profitable AI products used by 10K+ people"
    var targetDate: Long,
    var createdAt: Long,
    var lastReviewedAt: Long
) {
    lateinit var visions: ToMany<Vision>
    lateinit var objectives: ToMany<StrategicObjective>
}
```
#### StrategicObjective
**Purpose**: Quarterly/annual outcome to achieve, concrete target that multiple efforts serve.
```kotlin
@Entity
data class StrategicObjective(
    @Id var id: Long = 0,
    var text: String,                // "Ship AmbientAI MVP with 100 daily workflows"
    var targetDate: Long,
    var status: ObjectiveStatus,     // ACTIVE, ACHIEVED, ABANDONED
    var createdAt: Long,
    var completedAt: Long?
) {
    lateinit var missions: ToMany<Mission>
    lateinit var keyResults: ToMany<KeyResult>
}
enum class ObjectiveStatus { ACTIVE, ACHIEVED, ABANDONED }
```
#### KeyResult (KRI)
**Purpose**: Outcome metric indicating objective progress - things that happen as result of your work.
```kotlin
@Entity
data class KeyResult(
    @Id var id: Long = 0,
    var metric: String,              // "Daily active workflows"
    var target: String,              // "100"
    var current: String?,            // Current value
    var unit: String,                // "workflows/day"
    var lastUpdated: Long
) {
    lateinit var objectives: ToMany<StrategicObjective>  // Many-to-many
    lateinit var kpis: ToMany<KeyPerformanceIndicator>
}
```
#### KeyPerformanceIndicator (KPI)
**Purpose**: Activity metric you directly control - inputs that drive KRI outputs.
```kotlin
@Entity
data class KeyPerformanceIndicator(
    @Id var id: Long = 0,
    var name: String,                // "Implementation hours"
    var target: String,              // "20 hours/week"
    var unit: String,
    var frequency: String,           // "daily", "weekly", "monthly"
    var lastRecorded: Long
) {
    lateinit var keyResults: ToMany<KeyResult>  // Many-to-many
}
```
**Many-to-many rationale**: One KPI can drive multiple KRIs (coding hours → features + bug fixes). One KRI serves multiple objectives (revenue → profitability + validation).
### Supporting Entities
#### Plan
**Purpose**: Time-bound strategic declaration comparing coach prediction vs reality.
```kotlin
@Entity
data class Plan(
    @Id var id: Long = 0,
    var scope: PlanScope,            // DAILY, WEEKLY, MONTHLY
    var timeframe: String,           // "2025-11-10", "2025-W45"
    var declaration: String,         // What coach suggests
    var reasoning: String,           // Why this serves goals
    var expectedOutcome: String,     // What should happen
    var createdAt: Long
) {
    lateinit var suggestedTasks: ToMany<Task>              // Daily plans
    lateinit var affectedKPIs: ToMany<KeyPerformanceIndicator>  // Weekly
    lateinit var affectedKRIs: ToMany<KeyResult>           // Monthly
}
enum class PlanScope { DAILY, WEEKLY, MONTHLY }
```
**Plan hierarchy**:
- **Daily**: "Do X task because drives Y KPI"
- **Weekly**: "Adjust KPI targets because KRI not moving"
- **Monthly**: "Shift KRI focus because objective blocked"
#### CoachingSession
**Purpose**: Multi-turn conversation state for complex workflows.
```kotlin
@Entity
data class CoachingSession(
    @Id var id: Long = 0,
    var type: SessionType,           // GOAL_HIERARCHY_BUILDER, WEEKLY_REVIEW
    var state: String,               // JSON: progress tracking, drafts, confirmations
    var completed: Boolean = false
) {
    lateinit var transcripts: ToMany<Transcript>
}
enum class SessionType {
    GOAL_HIERARCHY_BUILDER,
    DAILY_PLANNER,
    WEEKLY_RETROSPECTIVE,
    MONTHLY_STRATEGY_REVIEW
}
```
**State tracks workflow progress**: current level, confirmed items, drafts awaiting confirmation.  
**Transcripts provide conversation context**: actual user/LLM exchanges.
### Coaching Workflows
#### 1. Goal Hierarchy Builder (Initialization)
**Trigger**: "Help me set up goals" or detected work without defined purpose  
**Mode**: Socratic (asking) → Directive (validation)
**Flow**: Vision → Mission → Objectives → KRIs → KPIs
**Process per level**:
1. Ask open questions
2. Reflect back what heard
3. Confirm with user
4. Create entity
5. Move to next level
   **Completion**: LLM determines sufficient information gathered at all levels, user confirms satisfaction.
   **State tracking**:
```json
{
  "currentLevel": "mission",
  "visionConfirmed": true,
  "visionId": 1,
  "missionDraft": "Launch profitable AI products",
  "awaitingConfirmation": false
}
```
#### 2. Daily Planner
**Trigger**: Morning workflow or starting work without plan  
**Mode**: Directive with confirmation
**Required state**:
- Active strategic objectives
- KPI targets
- Task backlog
- Recent narrative (blockers)
  **Flow**:
1. Synthesize: "Your objectives are X, Y, Z"
2. Propose: "Today focus on A because serves objective B"
3. Generate Plan entity with reasoning
4. Confirm alignment with user priorities
#### 3. Retrospective
**Trigger**: End of day/week, task completion, or manual  
**Mode**: Socratic → Directive
**Required state**:
- Plan (what was intended)
- Tasks completed (what happened)
- Time allocation
- KPI/KRI movement
  **Flow**:
1. Ask: "How did today go?"
2. Observe: "Spent 3 hours on X but said Y was priority"
3. Question: "What caused the shift?"
4. Advise: "For tomorrow, consider..."
5. Update KPI actual values
6. Update KRI progress
7. Save reflection as Narrative
### Actions (Workflow Capabilities)
#### Session Management
- `coaching.session.getOrCreate` - Load existing or create new by type
- `coaching.session.update` - Modify session state
- `coaching.session.complete` - Mark done
#### Goal CRUD
- `goals.vision.get` / `goals.vision.set`
- `goals.mission.get` / `goals.mission.set`
- `goals.objective.create` / `goals.objective.update`
- `goals.keyResult.create`
- `goals.kpi.create`
- `goals.link` - Create many-to-many relationships
#### Query Operations
- `goals.objectives.getActive` - Current objectives only
- `goals.kpis.getTargets` - Target values
- `goals.kris.getCurrent` - Current values
- `plan.create` / `plan.getLatest`
### LLM Response Schema
Coach workflows return structured responses:
```typescript
{
  "action": 
    | "ask"              // Gather information
    | "reflect"          // Mirror back understanding
    | "advise"           // Give recommendation
    | "create_vision" | "create_mission" | "create_objective" 
    | "create_kri" | "create_kpi"
    | "link_entities"    // Create relationships
    | "complete_session",
    
  "content": string | object,  // Message to user OR entity data
  
  "sessionStateUpdate"?: object  // Optional state modifications
}
```
**Examples**:
```json
// Asking
{
  "action": "ask",
  "content": "What do you want to achieve in 3 years?"
}
// Creating entity
{
  "action": "create_vision",
  "content": {
    "text": "Build technology that amplifies human potential"
  }
}
// Advising with tracking
{
  "action": "advise",
  "content": "Your 'hours coding' KPI isn't moving 'features shipped' KRI. Try tracking 'features completed' instead.",
  "sessionStateUpdate": {
    "adviceGiven": ["kpi_effectiveness"]
  }
}
```
### UI Components
#### Hierarchical Goal View
```
Vision: "Build technology that amplifies human potential"
└─ Mission: "Launch profitable AI products" (target: Q4 2026)
   ├─ Objective: "Ship AmbientAI MVP" (Q1 2026) [ACTIVE]
   │  ├─ KRI: Daily workflows = 100 (current: 20)
   │  │  ├─ KPI: Implementation hours = 20/week
   │  │  └─ KPI: User testing = 5/week
   │  └─ KRI: User retention = 70% (current: 45%)
   └─ Objective: "Reach profitability" (Q3 2026) [ACTIVE]
```
#### Session Review (Post-completion)
```
Session: Goal Hierarchy Builder
Duration: 24 minutes
Status: ✓ Complete
Changes proposed:
✓ Vision: "Build technology..."        [Accept] [Modify] [Reject]
✓ Mission: "Launch profitable..."       [Accept] [Modify] [Reject]
✓ Objective: "Ship AmbientAI MVP"      [Accept] [Modify] [Reject]
✓ KRI: "100 daily workflows"           [Accept] [Modify] [Reject]
[Accept All] [Review Individual Changes]
```
### Implementation Notes
#### Multi-turn Pattern
Each workflow invocation is atomic:
1. User triggers workflow
2. Load or create CoachingSession
3. Call LLM with tools + session state + transcripts
4. LLM decides: ask / create entity / complete
5. Update session state
6. Return response
7. Workflow ends
   Continuity comes from session state + linked transcripts, not long-running processes.
#### State vs Context
- **State**: Structured progress tracking (`{"visionConfirmed": true}`)
- **Context**: Conversation transcripts (actual exchanges)
  LLM uses both: state for "what's done", transcripts for "what was said".
#### Advice Tracking
`adviceGiven` in session state enables learning loop:
- Did user follow advice?
- Did it improve outcomes?
- Pattern: what advice works for this user?
  Can be used for grading coach effectiveness over time.
### Research Foundation
Personal mission/vision statements are well-established for individual goal setting. Vision describes desired future state (10+ years), mission describes present actions toward that vision. Annual review recommended as goals evolve.
Individual alignment with personal purpose drives intrinsic motivation beyond external factors. Framework successfully adapted from organizational strategy to personal development.
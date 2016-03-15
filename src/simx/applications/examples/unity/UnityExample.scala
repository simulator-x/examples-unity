/*
 * Copyright 2014 The SIRIS Project
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 *
 * The SIRIS Project is a cooperation between Beuth University, Berlin and the
 * HCI Group at the University of Würzburg. The project is funded by the German
 * Federal Ministry of Education and Research (grant no. 17N4409).
 */

package simx.applications.examples.unity

/**
 * Created by dennis on 21.05.14.
 */
/*
 * Copyright 2012 The SIRIS Project
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 *
 * The SIRIS Project is a cooperation between Beuth University, Berlin and the
 * HCI Group at the University of Würzburg. The project is funded by the German
 * Federal Ministry of Education and Research (grant no. 17N4409).
 */

import util.Random
import collection.immutable
import simplex3d.math.float._
import simx.components.renderer.unity.UnityComponentAspect
import simx.components.renderer.jvr.{JVRPickEvent, JVRComponentAspect, JVRInit}
import simx.core.{SimXApplicationMain, SimXApplication, ApplicationConfig}
import simx.components.sound.{LWJGLSoundComponentAspect, OpenALInit}
import simx.core.worldinterface.eventhandling.{EventHandler, EventProvider}
import simx.core.entity.Entity
import simx.core.svaractor.SVarActor
import simx.core.component.remote.RemoteCreation
import simx.core.component.{ExecutionStrategy, Soft}
import simx.core.ontology.{types => gt}
import simx.components.editor.EditorComponentAspect
import simx.core.components.physics.PhysSphere
import simx.core.components.io.SpeechEvents
import simx.components.physics.jbullet.JBulletComponentAspect
import simx.applications.examples.unity.objects.{Sounds, Light, Table, Ball}

/**
 * TODO: Document
 * @author Dennis Wiebusch, Martin Fischbach
 */
object UnityExample extends SimXApplicationMain[UnityExample] {
  val useEditor = false//askForOption("Use Editor Component?")
}

class UnityExample(args : Array[String]) extends SimXApplication
with JVRInit with OpenALInit with RemoteCreation with EventProvider with EventHandler
{
  //Component names
  val physicsName = 'physics
  val editorName = 'editor
  val soundName = 'sound
  val gfxName = 'renderer

  override protected def applicationConfiguration = ApplicationConfig withComponent
    JVRComponentAspect(gfxName) /*on "renderNode"*/ and
    JBulletComponentAspect(physicsName, ConstVec3(0, -9.81f, 0)) /*on "physicsNode"*/ and
    LWJGLSoundComponentAspect(soundName) /*on "soundNode"*/ and
    UnityComponentAspect('unity, "localhost", 8000) and
    EditorComponentAspect(editorName, appName = "MasterControlProgram") iff UnityExample.useEditor

  protected def configureComponents(components: immutable.Map[Symbol, SVarActor.Ref]) {
    exitOnClose(components(gfxName), shutdown) // register for exit on close
    start(ExecutionStrategy where
      components(physicsName) runs Soft(60) and
      components(gfxName) runs Soft(60)  and
      components(soundName) runs Soft(60)
      //where
      //components(physicsName) isTriggeredBy components(gfxName) and
      //components(gfxName) isTriggeredBy components(physicsName) startWith Set(components(physicsName))
    )
  }

  private var tableEntityOption: Option[Entity] = None
  private val ballRadius = 0.2f
  private val ballPosition = ConstVec3(0f, 1.5f, -7f)

  protected def createEntities() {
    Sounds.init()

    Light("the light", Vec3(-4f, 8f, -7f), Vec3(270f, -25f, 0f)).realize(entityComplete)
    Table("the table", Vec3(3f, 1f, 2f), Vec3(0f, -1.5f, -7f)).realize((tableEntity: Entity) => {
      entityComplete(tableEntity)
      tableEntityOption = Some(tableEntity)
    })
  }

  private def entityComplete(e: Entity) {
    println("[info][ExampleApplication] Completed entity " + e)
  }

protected def removeFromLocalRep(e : Entity){
  println("[info][ExampleApplication] Removed entity " + e)
}

  private var unityCam : Option[Entity] = None

  protected def finishConfiguration() {
    handleOrWaitForEntityRegistration('unity :: 'camera :: Symbol("1") :: Nil){ e => unityCam = Some(e) }

    rotateTable()
    initializePicking()
    initializeBallSpawning()
    initializeMouseControl()
    SpeechEvents.token.observe{ event =>
      val text = event.values.firstValueFor(gt.String)
      println("[info][ExampleApplication] Test event received. Contained sting is: " + text)
    }
    println("[info][ExampleApplication] Application is running: Press SPACE to spawn new balls!")
  }

  private def initializePicking(){
    var clickedOnce = Set[Entity]()
    JVRPickEvent.observe{
      _.get(gt.Entity).collect{
        case entity if !clickedOnce.contains(entity) =>
          println("picked " + entity)
          clickedOnce = clickedOnce + entity
          entity.set(gt.Velocity(Vec3.Zero))
          entity.disableAspect(PhysSphere())
        case entity =>
          println("picked " + entity)
          clickedOnce = clickedOnce - entity
          entity.enableAspect(PhysSphere())
      }
    }
  }

  private def rotateTable() {
    addJobIn(16L){
      tableEntityOption.collect{ case tableEntity =>
        //In complex applications it is reasonable to check if the list is not empty, rather than just call 'head'
        tableEntity.get(gt.Transformation).head(
          currentTransform => tableEntity.set(gt.Transformation(rotate(currentTransform))))
      }
      rotateTable()
    }
  }

  private def rotate(mat: ConstMat4) =
    mat * ConstMat4(Mat4x3.rotateY(0.01f))

  private def initializeBallSpawning() {
    handleDevice(gt.Keyboard){ keyboardEntity =>
      keyboardEntity.observe(gt.Key_Space).head( pressed => if(pressed) spawnBall() )
      keyboardEntity.observe(gt.Key_t).head{ pressed =>
        if(pressed) {
          println("[info][ExampleApplication] Test event emitted")
          SpeechEvents.token.emit(gt.String("test"), gt.Time(10L))
        }
      }
    }
  }

  private var ballCounter = 0

  private def spawnBall() {
    ballCounter += 1
    val randomOffset = Vec3(Random.nextFloat(), Random.nextFloat(), Random.nextFloat()) * 0.05f
    Ball("ball#" + ballCounter, ballRadius, ballPosition + randomOffset) realize {
      newBallEntity => newBallEntity.observe(gt.Transformation).head {
        newTransform => if(extractHeight(newTransform) < -2f) newBallEntity.remove()
      }
    }
  }

  private def extractHeight(mat: ConstMat4) = mat.m31

  private var userEntityOption: Option[Entity] = None

  def doIt(userEntity : Entity){
    userEntityOption = Some(userEntity)
  }

  private def initializeMouseControl() {
    handleDevice(gt.User)(doIt)
    handleDevice(gt.Mouse){ mouseEntity =>
      mouseEntity.observe(gt.Position2D).head{
        newMousePosition =>
          userEntityOption.collect{ case userEntity => userEntity.set(gt.ViewPlatform(calculateView(newMousePosition))) }
          //unityCam.collect{ case unityCamera => unityCamera.set(gt.Transformation(calculateView(newMousePosition))) }
      }
    }
  }

  private def calculateView(mousePos: ConstVec2) = {
    val weight = 0.1f
    val angleHorizontal = ((mousePos.x - 400f) / -400f) * weight
    val angleVertical = ((mousePos.y - 300f) / -300f) * weight
    ConstMat4(Mat4x3.rotateY(angleHorizontal).rotateX(angleVertical))
  }
}





/*
 * opsu! - an open-source osu! client
 * Copyright (C) 2014, 2015 Jeffrey Han
 *
 * opsu! is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * opsu! is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with opsu!.  If not, see <http://www.gnu.org/licenses/>.
 */

package itdelatrisu.opsu.objects;

import itdelatrisu.opsu.GameData;
import itdelatrisu.opsu.GameData.HitObjectType;
import itdelatrisu.opsu.GameImage;
import itdelatrisu.opsu.GameMod;
import itdelatrisu.opsu.Options;
import itdelatrisu.opsu.Utils;
import itdelatrisu.opsu.beatmap.Beatmap;
import itdelatrisu.opsu.beatmap.HitObject;
import itdelatrisu.opsu.objects.curves.CatmullCurve;
import itdelatrisu.opsu.objects.curves.CircumscribedCircle;
import itdelatrisu.opsu.objects.curves.Curve;
import itdelatrisu.opsu.objects.curves.LinearBezier;
import itdelatrisu.opsu.states.Game;
import itdelatrisu.opsu.ui.Colors;

import org.newdawn.slick.Color;
import org.newdawn.slick.GameContainer;
import org.newdawn.slick.Graphics;
import org.newdawn.slick.Image;

/**
 * Data type representing a slider object.
 */
public class Slider implements GameObject {
	/** Slider ball frames. */
	private static Image[] sliderBallImages;

	/** Slider movement speed multiplier. */
	private static float sliderMultiplier = 1.0f;

	/** Rate at which slider ticks are placed. */
	private static float sliderTickRate = 1.0f;

	/** Follow circle radius. */
	private static float followRadius;

	/** The diameter of hit circles. */
	private static float diameter;

	/** The associated HitObject. */
	private HitObject hitObject;

	/** The scaled starting x, y coordinates. */
	protected float x, y;

	/** The associated Game object. */
	private Game game;

	/** The associated GameData object. */
	private GameData data;

	/** The color of this slider. */
	private Color color;

	/** The underlying Curve. */
	private Curve curve;

	/** The time duration of the slider, in milliseconds. */
	private float sliderTime = 0f;

	/** The time duration of the slider including repeats, in milliseconds. */
	private float sliderTimeTotal = 0f;

	/** Whether or not the result of the initial hit circle has been processed. */
	private boolean sliderClickedInitial = false;

	/** Whether or not the slider was held to the end. */
	private boolean sliderHeldToEnd = false;

	/** Whether or not to show the follow circle. */
	private boolean followCircleActive = false;

	/** Whether or not the slider result ends the combo streak. */
	private boolean comboEnd;

	/** The number of repeats that have passed so far. */
	private int currentRepeats = 0;

	/** The t values of the slider ticks. */
	private float[] ticksT;

	/** The tick index in the ticksT[] array. */
	private int tickIndex = 0;

	/** Number of ticks hit and tick intervals so far. */
	private int ticksHit = 0, tickIntervals = 1;

	/** Container dimensions. */
	private static int containerWidth, containerHeight;

	/**
	 * Initializes the Slider data type with images and dimensions.
	 * @param container the game container
	 * @param circleSize the map's circleSize value
	 * @param beatmap the associated beatmap
	 */
	public static void init(GameContainer container, float circleSize, Beatmap beatmap) {
		containerWidth = container.getWidth();
		containerHeight = container.getHeight();

		diameter = (104 - (circleSize * 8));
		diameter = (diameter * HitObject.getXMultiplier());  // convert from Osupixels (640x480)
		int diameterInt = (int) diameter;

		followRadius = diameter / 2 * 3f;

		// slider ball
		if (GameImage.SLIDER_BALL.hasBeatmapSkinImages() ||
		    (!GameImage.SLIDER_BALL.hasBeatmapSkinImage() && GameImage.SLIDER_BALL.getImages() != null))
			sliderBallImages = GameImage.SLIDER_BALL.getImages();
		else
			sliderBallImages = new Image[]{ GameImage.SLIDER_BALL.getImage() };
		for (int i = 0; i < sliderBallImages.length; i++)
			sliderBallImages[i] = sliderBallImages[i].getScaledCopy(diameterInt * 118 / 128, diameterInt * 118 / 128);

		GameImage.SLIDER_FOLLOWCIRCLE.setImage(GameImage.SLIDER_FOLLOWCIRCLE.getImage().getScaledCopy(diameterInt * 259 / 128, diameterInt * 259 / 128));
		GameImage.REVERSEARROW.setImage(GameImage.REVERSEARROW.getImage().getScaledCopy(diameterInt, diameterInt));
		GameImage.SLIDER_TICK.setImage(GameImage.SLIDER_TICK.getImage().getScaledCopy(diameterInt / 4, diameterInt / 4));

		sliderMultiplier = beatmap.sliderMultiplier;
		sliderTickRate = beatmap.sliderTickRate;
	}

	/**
	 * Constructor.
	 * @param hitObject the associated HitObject
	 * @param game the associated Game object
	 * @param data the associated GameData object
	 * @param color the color of this slider
	 * @param comboEnd true if this is the last hit object in the combo
	 */
	public Slider(HitObject hitObject, Game game, GameData data, Color color, boolean comboEnd) {
		this.hitObject = hitObject;
		this.game = game;
		this.data = data;
		this.color = color;
		this.comboEnd = comboEnd;
		updatePosition();

		// slider time calculations
		this.sliderTime = game.getBeatLength() * (hitObject.getPixelLength() / sliderMultiplier) / 100f;
		this.sliderTimeTotal = sliderTime * hitObject.getRepeatCount();

		// ticks
		float tickLengthDiv = 100f * sliderMultiplier / sliderTickRate / game.getTimingPointMultiplier();
		int tickCount = (int) Math.ceil(hitObject.getPixelLength() / tickLengthDiv) - 1;
		if (tickCount > 0) {
			this.ticksT = new float[tickCount];
			float tickTOffset = 1f / (tickCount + 1);
			float t = tickTOffset;
			for (int i = 0; i < tickCount; i++, t += tickTOffset)
				ticksT[i] = t;
		}
	}

	@Override
	public void draw(Graphics g, int trackPosition) {
		int timeDiff = hitObject.getTime() - trackPosition;
		final int approachTime = game.getApproachTime();
		final int fadeInTime = game.getFadeInTime();
		float scale = timeDiff / (float) approachTime;
		float approachScale = 1 + scale * 3;
		float fadeinScale = (timeDiff - approachTime + fadeInTime) / (float) fadeInTime;
		float alpha = Utils.clamp(1 - fadeinScale, 0, 1);
		boolean overlayAboveNumber = Options.getSkin().isHitCircleOverlayAboveNumber();

		float oldAlpha = Colors.WHITE_FADE.a;
		Colors.WHITE_FADE.a = color.a = alpha;
		Image hitCircleOverlay = GameImage.HITCIRCLE_OVERLAY.getImage();
		Image hitCircle = GameImage.HITCIRCLE.getImage();
		float[] endPos = curve.pointAt(1);

		curve.draw(color);
		color.a = alpha;

		// end circle
		hitCircle.drawCentered(endPos[0], endPos[1], color);
		hitCircleOverlay.drawCentered(endPos[0], endPos[1], Colors.WHITE_FADE);

		// start circle
		hitCircle.drawCentered(x, y, color);
		if (!overlayAboveNumber)
			hitCircleOverlay.drawCentered(x, y, Colors.WHITE_FADE);

		// ticks
		if (ticksT != null) {
			Image tick = GameImage.SLIDER_TICK.getImage();
			for (int i = 0; i < ticksT.length; i++) {
				float[] c = curve.pointAt(ticksT[i]);
				tick.drawCentered(c[0], c[1], Colors.WHITE_FADE);
			}
		}
		if (GameMod.HIDDEN.isActive()) {
			final int hiddenDecayTime = game.getHiddenDecayTime();
			final int hiddenTimeDiff = game.getHiddenTimeDiff();
			if (fadeinScale <= 0f && timeDiff < hiddenTimeDiff + hiddenDecayTime) {
				float hiddenAlpha = (timeDiff < hiddenTimeDiff) ? 0f : (timeDiff - hiddenTimeDiff) / (float) hiddenDecayTime;
				alpha = Math.min(alpha, hiddenAlpha);
			}
		}
		if (sliderClickedInitial)
			;  // don't draw current combo number if already clicked
		else
			data.drawSymbolNumber(hitObject.getComboNumber(), x, y,
			        hitCircle.getWidth() * 0.40f / data.getDefaultSymbolImage(0).getHeight(), alpha);
		if (overlayAboveNumber)
			hitCircleOverlay.drawCentered(x, y, Colors.WHITE_FADE);

		// repeats
		for (int tcurRepeat = currentRepeats; tcurRepeat <= currentRepeats + 1; tcurRepeat++) {
			if (hitObject.getRepeatCount() - 1 > tcurRepeat) {
				Image arrow = GameImage.REVERSEARROW.getImage();
				if (tcurRepeat != currentRepeats) {
					if (sliderTime == 0)
						continue;
					float t = Math.max(getT(trackPosition, true), 0);
					arrow.setAlpha((float) (t - Math.floor(t)));
				} else
					arrow.setAlpha(1f);
				if (tcurRepeat % 2 == 0) {
					// last circle
					arrow.setRotation(curve.getEndAngle());
					arrow.drawCentered(endPos[0], endPos[1]);
				} else {
					// first circle
					arrow.setRotation(curve.getStartAngle());
					arrow.drawCentered(x, y);
				}
			}
		}

		if (timeDiff >= 0) {
			// approach circle
			if (!GameMod.HIDDEN.isActive())
				GameImage.APPROACHCIRCLE.getImage().getScaledCopy(approachScale).drawCentered(x, y, color);
		} else {
			// Since update() might not have run before drawing during a replay, the
			// slider time may not have been calculated, which causes NAN numbers and flicker.
			if (sliderTime == 0)
				return;

			float[] c = curve.pointAt(getT(trackPosition, false));
			float[] c2 = curve.pointAt(getT(trackPosition, false) + 0.01f);

			float t = getT(trackPosition, false);
//			float dis = hitObject.getPixelLength() * HitObject.getXMultiplier() * (t - (int) t);
//			Image sliderBallFrame = sliderBallImages[(int) (dis / (diameter * Math.PI) * 30) % sliderBallImages.length];
			Image sliderBallFrame = sliderBallImages[(int) (t * sliderTime * 60 / 1000) % sliderBallImages.length];
			float angle = (float) (Math.atan2(c2[1] - c[1], c2[0] - c[0]) * 180 / Math.PI);
			sliderBallFrame.setRotation(angle);
			sliderBallFrame.drawCentered(c[0], c[1]);

			// follow circle
			if (followCircleActive) {
				GameImage.SLIDER_FOLLOWCIRCLE.getImage().drawCentered(c[0], c[1]);

				// "flashlight" mod: dim the screen
				if (GameMod.FLASHLIGHT.isActive()) {
					float oldAlphaBlack = Colors.BLACK_ALPHA.a;
					Colors.BLACK_ALPHA.a = 0.75f;
					g.setColor(Colors.BLACK_ALPHA);
					g.fillRect(0, 0, containerWidth, containerHeight);
					Colors.BLACK_ALPHA.a = oldAlphaBlack;
				}
			}
		}

		Colors.WHITE_FADE.a = oldAlpha;
	}

	/**
	 * Calculates the slider hit result.
	 * @return the hit result (GameData.HIT_* constants)
	 */
	private int hitResult() {
		/*
			time     scoredelta score-hit-initial-tick= unaccounted
			(1/4   - 1)		396 - 300 - 30	 		46
			(1+1/4 - 2)		442 - 300 - 30 - 10
			(2+1/4 - 3)		488 - 300 - 30 - 2*10	896 (408)5x
			(3+1/4 - 4)		534 - 300 - 30 - 3*10
			(4+1/4 - 5)		580 - 300 - 30 - 4*10
			(5+1/4 - 6) 	626	- 300 - 30 - 5*10
			(6+1/4 - 7)		672	- 300 - 30 - 6*10

			difficultyMulti = 3	(+36 per combo)

			score =
			(t)ticks(10) * nticks +
			(h)hitValue
			(c)combo (hitValue/25 * difficultyMultiplier*(combo-1))
			(i)initialHit (30) +
			(f)finalHit(30) +

			s     t       h          c     i     f
			626 - 10*5 - 300  - 276(-216 - 30 - 30) (all)(7x)
			240 - 10*5 - 100  - 90 (-60     <- 30>) (no final or initial)(6x)

			218 - 10*4 - 100  - 78 (-36       - 30) (4 tick no initial)(5x)
			196 - 10*3 - 100  - 66 (-24       - 30 ) (3 tick no initial)(4x)
			112 - 10*2 - 50   - 42 (-12       - 30 ) (2 tick no initial)(3x)
			96  - 10   - 50   - 36 ( -6       - 30 ) (1 tick no initial)(2x)

			206 - 10*4 - 100  - 66 (-36       - 30 ) (4 tick no initial)(4x)
			184 - 10*3 - 100  - 54 (-24       - 30 ) (3 tick no initial)(3x)
			90  - 10   - 50   - 30 (          - 30 ) (1 tick no initial)(0x)

			194 - 10*4 - 100  - 54 (-24       - 30 ) (4 tick no initial)(3x)

			170 - 10*4 - 100  - 30 (     - 30      ) (4 tick no final)(0x)
			160 - 10*3 - 100  - 30 (     - 30      ) (3 tick no final)(0x)
			100 - 10*2 - 50   - 30 (     - 30      ) (2 tick no final)(0x)

			198 - 10*5 - 100  - 48 (-36            ) (no initial and final)(5x)
			110        - 50   -    (     - 30 - 30 ) (final and initial no tick)(0x)
			80         - 50   -    (       <- 30>  ) (only final or initial)(0x)

			140 - 10*4 - 100  - 0                    (4 ticks only)(0x)
			80  - 10*3 - 50   - 0                    (3 tick only)(0x)
			70  - 10*2 - 50   - 0                    (2 tick only)(0x)
			60  - 10   - 50   - 0                    (1 tick only)(0x)
		*/
		float tickRatio = (float) ticksHit / tickIntervals;

		int result;
		if (tickRatio >= 1.0f)
			result = GameData.HIT_300;
		else if (tickRatio >= 0.5f)
			result = GameData.HIT_100;
		else if (tickRatio > 0f)
			result = GameData.HIT_50;
		else
			result = GameData.HIT_MISS;

		float cx, cy;
		HitObjectType type;
		if (currentRepeats % 2 == 0) {  // last circle
			float[] lastPos = curve.pointAt(1);
			cx = lastPos[0];
			cy = lastPos[1];
			type = HitObjectType.SLIDER_LAST;
		} else {  // first circle
			cx = x;
			cy = y;
			type = HitObjectType.SLIDER_FIRST;
		}
		data.hitResult(hitObject.getTime() + (int) sliderTimeTotal, result,
				cx, cy, color, comboEnd, hitObject, type, sliderHeldToEnd,
				currentRepeats + 1, curve, sliderHeldToEnd);

		return result;
	}

	@Override
	public boolean mousePressed(int x, int y, int trackPosition) {
		if (sliderClickedInitial)  // first circle already processed
			return false;

		double distance = Math.hypot(this.x - x, this.y - y);
		if (distance < diameter / 2) {
			int timeDiff = Math.abs(trackPosition - hitObject.getTime());
			int[] hitResultOffset = game.getHitResultOffsets();

			int result = -1;
			if (timeDiff < hitResultOffset[GameData.HIT_50]) {
				result = GameData.HIT_SLIDER30;
				ticksHit++;
			} else if (timeDiff < hitResultOffset[GameData.HIT_MISS])
				result = GameData.HIT_MISS;
			//else not a hit

			if (result > -1) {
				data.addHitError(hitObject.getTime(), x,y,trackPosition - hitObject.getTime());
				sliderClickedInitial = true;
				data.sliderTickResult(hitObject.getTime(), result, this.x, this.y, hitObject, currentRepeats);
				return true;
			}
		}
		return false;
	}

	@Override
	public boolean update(boolean overlap, int delta, int mouseX, int mouseY, boolean keyPressed, int trackPosition) {
		int repeatCount = hitObject.getRepeatCount();
		int[] hitResultOffset = game.getHitResultOffsets();
		boolean isAutoMod = GameMod.AUTO.isActive();

		if (!sliderClickedInitial) {
			int time = hitObject.getTime();

			// start circle time passed
			if (trackPosition > time + hitResultOffset[GameData.HIT_50]) {
				sliderClickedInitial = true;
				if (isAutoMod) {  // "auto" mod: catch any missed notes due to lag
					ticksHit++;
					data.sliderTickResult(time, GameData.HIT_SLIDER30, x, y, hitObject, currentRepeats);
				} else
					data.sliderTickResult(time, GameData.HIT_MISS, x, y, hitObject, currentRepeats);
			}

			// "auto" mod: send a perfect hit result
			else if (isAutoMod) {
				if (Math.abs(trackPosition - time) < hitResultOffset[GameData.HIT_300]) {
					ticksHit++;
					sliderClickedInitial = true;
					data.sliderTickResult(time, GameData.HIT_SLIDER30, x, y, hitObject, currentRepeats);
				}
			}

			// "relax" mod: click automatically
			else if (GameMod.RELAX.isActive() && trackPosition >= time)
				mousePressed(mouseX, mouseY, trackPosition);
		}

		// end of slider
		if (trackPosition > hitObject.getTime() + sliderTimeTotal) {
			tickIntervals++;

			// check if cursor pressed and within end circle
			if (keyPressed || GameMod.RELAX.isActive()) {
				float[] c = curve.pointAt(getT(trackPosition, false));
				double distance = Math.hypot(c[0] - mouseX, c[1] - mouseY);
				if (distance < followRadius)
					sliderHeldToEnd = true;
			}

			// final circle hit
			if (sliderHeldToEnd)
				ticksHit++;

			// "auto" mod: always send a perfect hit result
			if (isAutoMod)
				ticksHit = tickIntervals;

			// calculate and send slider result
			hitResult();
			return true;
		}

		// repeats
		boolean isNewRepeat = false;
		if (repeatCount - 1 > currentRepeats) {
			float t = getT(trackPosition, true);
			if (Math.floor(t) > currentRepeats) {
				currentRepeats++;
				tickIntervals++;
				isNewRepeat = true;
			}
		}

		// ticks
		boolean isNewTick = false;
		if (ticksT != null &&
			tickIntervals < (ticksT.length * (currentRepeats + 1)) + repeatCount &&
			tickIntervals < (ticksT.length * repeatCount) + repeatCount) {
			float t = getT(trackPosition, true);
			if (t - Math.floor(t) >= ticksT[tickIndex]) {
				tickIntervals++;
				tickIndex = (tickIndex + 1) % ticksT.length;
				isNewTick = true;
			}
		}

		// holding slider...
		float[] c = curve.pointAt(getT(trackPosition, false));
		double distance = Math.hypot(c[0] - mouseX, c[1] - mouseY);
		if (((keyPressed || GameMod.RELAX.isActive()) && distance < followRadius) || isAutoMod) {
			// mouse pressed and within follow circle
			followCircleActive = true;

			// held during new repeat
			if (isNewRepeat) {
				ticksHit++;
				if (currentRepeats % 2 > 0) {  // last circle
					int lastIndex = hitObject.getSliderX().length;
					data.sliderTickResult(trackPosition, GameData.HIT_SLIDER30,
							curve.getX(lastIndex), curve.getY(lastIndex), hitObject, currentRepeats);
				} else  // first circle
					data.sliderTickResult(trackPosition, GameData.HIT_SLIDER30,
							c[0], c[1], hitObject, currentRepeats);
			}

			// held during new tick
			if (isNewTick) {
				ticksHit++;
				data.sliderTickResult(trackPosition, GameData.HIT_SLIDER10,
						c[0], c[1], hitObject, currentRepeats);
			}

			// held near end of slider
			if (!sliderHeldToEnd && trackPosition > hitObject.getTime() + sliderTimeTotal - hitResultOffset[GameData.HIT_300])
				sliderHeldToEnd = true;
		} else {
			followCircleActive = false;

			if (isNewRepeat)
				data.sliderTickResult(trackPosition, GameData.HIT_MISS, 0, 0, hitObject, currentRepeats);
			if (isNewTick)
				data.sliderTickResult(trackPosition, GameData.HIT_MISS, 0, 0, hitObject, currentRepeats);
		}

		return false;
	}

	@Override
	public void updatePosition() {
		this.x = hitObject.getScaledX();
		this.y = hitObject.getScaledY();

		if (hitObject.getSliderType() == HitObject.SLIDER_PASSTHROUGH && hitObject.getSliderX().length == 2)
			this.curve = new CircumscribedCircle(hitObject, color);
		else if (hitObject.getSliderType() == HitObject.SLIDER_CATMULL)
			this.curve = new CatmullCurve(hitObject, color);
		else
			this.curve = new LinearBezier(hitObject, color, hitObject.getSliderType() == HitObject.SLIDER_LINEAR);
	}

	@Override
	public float[] getPointAt(int trackPosition) {
		if (trackPosition <= hitObject.getTime())
			return new float[] { x, y };
		else if (trackPosition >= hitObject.getTime() + sliderTimeTotal) {
			if (hitObject.getRepeatCount() % 2 == 0)
				return new float[] { x, y };
			else
				return curve.pointAt(1);
		} else
			return curve.pointAt(getT(trackPosition, false));
	}

	@Override
	public int getEndTime() { return hitObject.getTime() + (int) sliderTimeTotal; }

	/**
	 * Returns the t value based on the given track position.
	 * @param trackPosition the current track position
	 * @param raw if false, ensures that the value lies within [0, 1] by looping repeats
	 * @return the t value: raw [0, repeats] or looped [0, 1]
	 */
	private float getT(int trackPosition, boolean raw) {
		float t = (trackPosition - hitObject.getTime()) / sliderTime;
		if (raw)
			return t;
		else {
			float floor = (float) Math.floor(t);
			return (floor % 2 == 0) ? t - floor : floor + 1 - t;
		}
	}

	@Override
	public void reset() {
		sliderClickedInitial = false;
		sliderHeldToEnd = false;
		followCircleActive = false;
		currentRepeats = 0;
		tickIndex = 0;
		ticksHit = 0;
		tickIntervals = 1;
	}
}
